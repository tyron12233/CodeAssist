package dev.ide.lang.kotlin

import dev.ide.kotlin.syntax.psi.KtCallExpression
import dev.ide.kotlin.syntax.psi.KtElement
import dev.ide.kotlin.syntax.psi.KtFile
import dev.ide.kotlin.syntax.psi.KtNameReferenceExpression
import dev.ide.kotlin.syntax.psi.KtNamedFunction
import dev.ide.kotlin.syntax.psi.KtProjectionKind
import dev.ide.kotlin.syntax.psi.KtQualifiedExpression
import dev.ide.kotlin.syntax.psi.KtTypeAlias
import dev.ide.kotlin.syntax.psi.KtTypeArgumentList
import dev.ide.kotlin.syntax.psi.KtTypeProjection
import dev.ide.kotlin.syntax.psi.KtUserType
import dev.ide.lang.dom.Diagnostic
import dev.ide.lang.dom.Severity
import dev.ide.lang.dom.TextRange
import dev.ide.lang.kotlin.parse.KotlinDomNode
import dev.ide.lang.kotlin.resolve.KotlinResolver
import dev.ide.lang.kotlin.resolve.callTargets
import dev.ide.lang.kotlin.resolve.constructorTypeFqn
import dev.ide.lang.kotlin.resolve.enclosingClassFqnOf
import dev.ide.lang.kotlin.resolve.isTypeParameterInScope
import dev.ide.lang.resolve.SymbolKind

/**
 * `foo<Int, String>(0)` against `fun <T> foo(t: T)`, `C<Int>()` against `class C`: a call's explicit type
 * arguments whose count fits no candidate. Judged only where the count is read exactly: function candidates
 * that all declare the same count (overloads that differ leave the compiler to report the call as a whole),
 * from source or with type parameters the classpath decoded (an empty list from a library may be a decode
 * gap), and a constructor of a project-source class, which takes exactly its class's type parameters. A
 * typealias constructor has the alias's own arity, which is left alone.
 */
internal fun KotlinSemanticChecks.callTypeArgumentCount(call: KtCallExpression, resolver: KotlinResolver): Diagnostic? {
    val argList = call.typeArgumentList ?: return null
    val actual = argList.arguments.size
    if (actual == 0) return null
    val callee = call.calleeExpression as? KtNameReferenceExpression ?: return null
    val name = callee.getReferencedName()
    if (name.isEmpty()) return null
    val targets = resolver.callTargets(call)
    // A typealias constructed by its name (`P<Int>(1, 2)`), unless a function of that name is what is called.
    if (targets.none { it.kind == SymbolKind.METHOD } && call.parent.let { it !is KtQualifiedExpression || it.selectorExpression !== call }) {
        typeAliasArgumentCount(name, argList, resolver)?.let { return it }
    }
    if (targets.isEmpty() || targets.any { it.name != name && it.kind != SymbolKind.CONSTRUCTOR }) return null
    val expected: Int = when {
        targets.all { it.kind == SymbolKind.CONSTRUCTOR } ->
            service.classTypeParameters(constructedSourceClass(call, resolver) ?: return null).size
        targets.any { it.kind != SymbolKind.METHOD } -> return null
        else -> {
            // Every candidate must agree on its count; overloads that differ leave the compiler to judge the call.
            val counts = targets.map { it.typeParameters.size }.distinct()
            val count = counts.singleOrNull() ?: return null
            if (count == 0 && targets.any { !it.origin.fromSource }) return null
            // A `<…>` written but not parsed into parameters (`fun <break> foo()`) is a syntax error of its own.
            if (count == 0 && targets.any { ((it.declaration() as? KotlinDomNode)?.psi as? KtNamedFunction)?.typeParameterList != null }) return null
            count
        }
    }
    if (expected == actual) return null
    val r = argList.textRange
    val msg = if (expected == 0) "'$name' does not take type arguments"
    else "$expected type argument${if (expected == 1) "" else "s"} expected for '$name', but $actual found"
    return Diagnostic(TextRange(r.startOffset, r.endOffset), Severity.ERROR, msg, KotlinDiagnosticCodes.TYPE_ARGUMENT_COUNT)
}

/** `P<Int>` / `P<Int>(1, 2)` where this file declares `typealias P<A, B> = …`: an alias takes exactly its own
 *  type parameters, whatever its expansion takes. */
internal fun typeAliasArgumentCount(aliasName: String, argList: KtTypeArgumentList, resolver: KotlinResolver): Diagnostic? {
    val file = argList.containingFile as? KtFile ?: return null
    if (resolver.isTypeParameterInScope(aliasName, argList.textRange.startOffset)) return null
    val alias = file.declarations.filterIsInstance<KtTypeAlias>().singleOrNull { it.name == aliasName } ?: return null
    val expected = alias.typeParameters.size
    val actual = argList.arguments.size
    if (expected == actual) return null
    val r = argList.textRange
    val msg = if (expected == 0) "Type alias '$aliasName' does not take type arguments"
    else "$expected type argument${if (expected == 1) "" else "s"} expected for '$aliasName', but $actual found"
    return Diagnostic(TextRange(r.startOffset, r.endOffset), Severity.ERROR, msg, KotlinDiagnosticCodes.TYPE_ARGUMENT_COUNT)
}

/**
 * `N<String>` / `N<String>("")` where `typealias N<T> = Num<T>` and `class Num<T : Number>`: an argument of the
 * ALIAS that lands, through the expansion, in a class type parameter whose bound it violates. The compiler's
 * UPPER_BOUND_VIOLATED_IN_TYPEALIAS_EXPANSION, reported on the argument as written. Followed through aliases of
 * aliases, for aliases this file declares (their parameters and right-hand sides are read off its PSI), and only
 * for an argument the alias passes straight through as a whole type argument of the expansion.
 */
internal fun KotlinSemanticChecks.typeAliasArgumentBounds(
    aliasName: String, args: List<KtTypeProjection>, at: KtElement, resolver: KotlinResolver,
): List<Diagnostic> {
    if (args.isEmpty()) return emptyList()
    val file = at.containingFile as? KtFile ?: return emptyList()
    val aliases = file.declarations.filterIsInstance<KtTypeAlias>().associateBy { it.name }
    var alias = aliases[aliasName] ?: return emptyList()
    if (resolver.isTypeParameterInScope(aliasName, at.textRange.startOffset)) return emptyList()
    // Which use-site argument each type argument of the current expansion is, when it is one unchanged.
    var slots: List<Int?> = args.indices.map { it }
    repeat(5) {
        val params = alias.typeParameters.mapNotNull { it.name }
        if (params.size != slots.size) return emptyList() // a count error, reported as one
        val rhs = alias.typeReference?.typeElement as? KtUserType ?: return emptyList()
        val rhsArgs = rhs.typeArguments
        val next = rhsArgs.map { proj ->
            val name = (proj.typeReference?.typeElement as? KtUserType)?.takeIf { it.qualifier == null && it.typeArgumentList == null }
                ?.referencedName
            params.indexOf(name).takeIf { it >= 0 }?.let { slots.getOrNull(it) }
        }
        val head = rhs.referencedName ?: return emptyList()
        if (rhs.qualifier == null) aliases[head]?.let { inner ->
            if (inner === alias) return emptyList()
            alias = inner
            slots = next
            return@repeat
        }
        val fqn = service.resolveTypeName(rhs.text.substringBefore('<').trim(), resolver.fileContext, enclosingClassFqnOf(rhs))
            ?: return emptyList()
        if (!service.isSourceClass(fqn)) return emptyList()
        val bounds = service.classTypeParameterBounds(fqn)
        val out = ArrayList<Diagnostic>()
        next.forEachIndexed { j, slot ->
            val bound = bounds.getOrNull(j) ?: return@forEachIndexed
            val proj = args.getOrNull(slot ?: return@forEachIndexed) ?: return@forEachIndexed
            if (proj.projectionKind == KtProjectionKind.STAR) return@forEachIndexed
            val argRef = proj.typeReference ?: return@forEachIndexed
            val argType = service.typeFromText(argRef.text, resolver.fileContext, enclosingClassFqnOf(argRef)) ?: return@forEachIndexed
            if (boundViolated(argType, bound)) {
                val r = argRef.textRange
                out += Diagnostic(
                    TextRange(r.startOffset, r.endOffset), Severity.ERROR,
                    "Type argument is not within its bounds in the expansion of '$aliasName': should be a subtype of '${renderType(bound)}'",
                    KotlinDiagnosticCodes.UPPER_BOUND_VIOLATED,
                )
            }
        }
        return out
    }
    return emptyList()
}

/** The project-source class a constructor call `C(…)` / `Outer.C(…)` builds, when every target of the call is a
 *  constructor and the name is not a typealias (whose own type parameters are the ones written). */
internal fun KotlinSemanticChecks.constructedSourceClass(call: KtCallExpression, resolver: KotlinResolver): String? {
    val callee = call.calleeExpression as? KtNameReferenceExpression ?: return null
    val name = callee.getReferencedName()
    if (name.firstOrNull()?.isUpperCase() != true) return null
    val targets = resolver.callTargets(call)
    if (targets.isEmpty() || targets.any { it.kind != SymbolKind.CONSTRUCTOR }) return null
    if (service.typeAliasTargetFqn(name, resolver.fileContext) != null) return null
    val q = call.parent as? KtQualifiedExpression
    val spelled = if (q != null && q.selectorExpression === call) q.receiverExpression.text + "." + name else name
    val fqn = resolver.constructorTypeFqn(spelled, callee.textRange.startOffset) ?: return null
    return fqn.takeIf { service.isSourceClass(it) }
}
