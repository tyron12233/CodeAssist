package dev.ide.lang.kotlin

import dev.ide.kotlin.syntax.psi.KtCallExpression
import dev.ide.kotlin.syntax.psi.KtCallableReferenceExpression
import dev.ide.kotlin.syntax.psi.KtConstantExpression
import dev.ide.kotlin.syntax.psi.KtExpression
import dev.ide.kotlin.syntax.psi.KtLambdaArgument
import dev.ide.kotlin.syntax.psi.KtLambdaExpression
import dev.ide.kotlin.syntax.psi.KtNamedFunction
import dev.ide.kotlin.syntax.psi.KtNameReferenceExpression
import dev.ide.kotlin.syntax.psi.KtQualifiedExpression
import dev.ide.kotlin.syntax.psi.KtStringTemplateExpression
import dev.ide.lang.dom.Diagnostic
import dev.ide.lang.dom.Severity
import dev.ide.lang.dom.TextRange
import dev.ide.lang.kotlin.parse.KotlinDomNode
import dev.ide.lang.kotlin.parse.unwrapParen
import dev.ide.lang.kotlin.resolve.KotlinConstraintSystem
import dev.ide.lang.kotlin.resolve.KotlinResolver
import dev.ide.lang.kotlin.resolve.callTargets
import dev.ide.lang.kotlin.resolve.discardedOrUntypedPosition
import dev.ide.lang.kotlin.resolve.enclosingCallAndParamIndex
import dev.ide.lang.kotlin.resolve.expectedLambdaShape
import dev.ide.lang.kotlin.resolve.inferType
import dev.ide.lang.kotlin.resolve.lambdaParamIndex
import dev.ide.lang.kotlin.resolve.resolveCalleeFunction
import dev.ide.lang.kotlin.symbols.KotlinType
import dev.ide.lang.resolve.SymbolKind

/**
 * A generic call whose arguments contradict its own type variables: `foo(list)` with `list: List<Int>` for
 * `fun <T : CharSequence> foo(list: List<T>)` (T must be above Int and below CharSequence), `doIn(i)` with
 * `i: In<Int>` for `fun <T : Any> doIn(i: In<T?>)` (T? would have to be below Int). K2's constraint system fails,
 * so the argument whose constraint introduced the contradiction is an ARGUMENT_TYPE_MISMATCH. When the
 * contradiction leaves a variable no type it may take (a floor above its declared bound, or a constraint
 * impossible at the variable itself), each type variable of that argument's parameter is uninferred,
 * CANNOT_INFER_PARAMETER_TYPE on the callee, where nothing outside the call could have constrained it (a
 * statement, or the initializer of a declaration with no written type); two arguments' bounds disagreeing only
 * make the later argument the mismatch (`bar(outA, inC)` for `fun <T> bar(o: Out<T>, i: In<T>)`).
 *
 * Judged by [KotlinConstraintSystem], which can only miss a contradiction, never invent one, for the one
 * candidate of a call that writes no type arguments and passes no lambda, callable reference or spread: each
 * argument's type must be read off the tree (a literal, a name with a written type, a constructor call) and
 * fully known. A member of a generic class is left alone (its receiver's arguments are not in the system).
 */
internal fun KotlinSemanticChecks.inferenceContradiction(call: KtCallExpression, resolver: KotlinResolver): List<Diagnostic> {
    if (call.typeArgumentList != null) return emptyList()
    val args = call.valueArguments
    if (args.isEmpty()) return emptyList()
    if (args.any { it is KtLambdaArgument || it.isSpread || it.getArgumentName() != null }) return emptyList()
    val callee = call.calleeExpression as? KtNameReferenceExpression ?: return emptyList()
    val sym = resolveCalleeFunction(call, resolver) ?: return emptyList()
    if (sym.kind != SymbolKind.METHOD || sym.typeParameters.isEmpty() || sym.varargParamIndex >= 0) return emptyList()
    if (sym.receiverTypeArgs.isNotEmpty() || sym.receiverTypeParam != null) return emptyList()
    sym.declaringClassFqn?.let { if (service.classTypeParameters(it).isNotEmpty()) return emptyList() }
    if (args.size > sym.paramTypes.size) return emptyList()
    // The one candidate: an overload could accept what this one rejects.
    val targets = runCatching { resolver.callTargets(call) }.getOrDefault(emptyList())
        .filter { it.kind == SymbolKind.METHOD || it.kind == SymbolKind.CONSTRUCTOR }
        .distinctBy { t ->
            val at = ((t.declaration() as? KotlinDomNode)?.psi)?.textRange?.startOffset
            if (t.origin.fromSource && at != null) Pair(t.origin.file?.toString(), at) else t
        }
    if (targets.size != 1) return emptyList()
    val names = sym.typeParameters.toSet()
    // The arguments' types, as written; any other argument leaves the call alone.
    val argTypes = args.map { a -> readKnownArgumentType(a.getArgumentExpression() ?: return emptyList(), resolver) ?: return emptyList() }
    val params = (0 until args.size).map { i ->
        val p = service.markTypeParameters(sym.paramTypes[i] as? KotlinType, names) ?: return emptyList()
        if (mentionsDefinitelyNonNull(p)) return emptyList()
        p
    }
    val cs = KotlinConstraintSystem(service, nullableVariableBelowNonNull = true)
    for ((i, tp) in sym.typeParameters.withIndex()) {
        val bound = service.markTypeParameters(sym.typeParameterBounds.getOrNull(i) as? KotlinType, names)
        // A bound naming another variable (`R : List<T>`) links them in ways this does not follow.
        cs.registerVariable(tp, bound?.takeIf { !mentionsAny(it, names) })
    }
    if (cs.hasContradiction) return emptyList()
    var culprit = -1
    for (i in args.indices) {
        cs.addSubtypeConstraint(argTypes[i], params[i])
        if (cs.hasContradiction) { culprit = i; break }
    }
    if (culprit < 0) return emptyList()
    val out = ArrayList<Diagnostic>()
    val value = args[culprit].getArgumentExpression()!!
    val r = value.textRange
    out += Diagnostic(
        TextRange(r.startOffset, r.endOffset), Severity.ERROR,
        "Argument type mismatch: actual type is '${renderType(argTypes[culprit])}', but '${renderType(params[culprit])}' was expected",
        KotlinDiagnosticCodes.TYPE_MISMATCH,
    )
    val whole: KtExpression = (call.parent as? KtQualifiedExpression)?.takeIf { it.selectorExpression === call } ?: call
    if (cs.variableLeftWithoutType && discardedOrUntypedPosition(whole)) {
        val cr = callee.textRange
        for (tp in sym.typeParameters.filter { mentionsAny(params[culprit], setOf(it)) }) {
            out += Diagnostic(
                TextRange(cr.startOffset, cr.endOffset), Severity.ERROR,
                "Cannot infer type for type parameter '$tp'. Specify it explicitly.",
                KotlinDiagnosticCodes.CANNOT_INFER_TYPE,
            )
        }
    }
    return out
}

private fun resolveCalleeFunction(call: KtCallExpression, resolver: KotlinResolver) =
    runCatching { resolver.resolveCalleeFunction(call) }.getOrNull()

/** An argument's type read off the tree, every part of it a known class: a literal, a name with a written type,
 *  or a constructor call of a class (with its type arguments written, if it takes any). Null for anything else. */
private fun KotlinSemanticChecks.readKnownArgumentType(e: KtExpression, resolver: KotlinResolver): KotlinType? {
    val u = unwrapParen(e)
    val t = when (u) {
        is KtLambdaExpression, is KtCallableReferenceExpression, is KtNamedFunction -> return null
        is KtConstantExpression -> if (u.text == "null") return null else resolver.inferType(u)
        is KtStringTemplateExpression -> resolver.inferType(u)
        // A vararg parameter's written type is its element's; the value is an array of them.
        is KtNameReferenceExpression ->
            if ((nearestLocalDecl(u.getReferencedName(), u.textRange.startOffset, u) as? dev.ide.kotlin.syntax.psi.KtParameter)?.isVarArg == true) return null
            else writtenTypeOf(u, resolver)
        is KtCallExpression -> {
            val name = (u.calleeExpression as? KtNameReferenceExpression)?.getReferencedName() ?: return null
            val fqn = service.resolveTypeName(name, resolver.fileContext) ?: return null
            if (service.sourceClass(fqn) == null) return null
            if (service.classTypeParameters(fqn).isNotEmpty() && u.typeArgumentList == null) return null
            resolver.inferType(u)?.takeIf { it.qualifiedName == fqn }
        }
        else -> null
    } ?: return null
    return t.takeIf { fullyKnown(it) }
}

/** Every part a known class and no use-site projection anywhere: the constraint system drops projections when it
 *  decomposes, so a projected argument (`Array<in Int>`) is not one it can judge. */
private fun KotlinSemanticChecks.fullyKnown(t: KotlinType): Boolean =
    !t.isTypeParameter && t.projection.isEmpty() && service.isKnownType(t.qualifiedName) &&
        t.typeArguments.all { (it as? KotlinType)?.let(::fullyKnown) == true }

private fun mentionsAny(t: KotlinType, names: Set<String>): Boolean =
    (t.isTypeParameter && t.qualifiedName in names) || t.typeArguments.any { (it as? KotlinType)?.let { a -> mentionsAny(a, names) } == true }

private fun mentionsDefinitelyNonNull(t: KotlinType): Boolean =
    t.definitelyNonNull || t.typeArguments.any { (it as? KotlinType)?.let(::mentionsDefinitelyNonNull) == true }

/**
 * CANNOT_INFER_VALUE_PARAMETER_TYPE on a lambda's untyped parameters past the arity of the function type expected
 * of it: `foo0 { x, y -> "" }` for `foo0(f: () -> String)` (both), `foo1 { x, y -> "" }` for `(Int) -> String`
 * (`y`), `val f: () -> Int = { it -> "" }`. The expected type gives such a parameter nothing, so K2 cannot type it
 * (the lambda itself is the argument or initializer mismatch). Judged where the expected type is a plain function
 * type or SAM with no receiver: a declared one, or the parameter of a call's one candidate.
 */
internal fun KotlinSemanticChecks.lambdaParametersPastExpectedArity(
    lambda: dev.ide.kotlin.syntax.psi.KtLambdaExpression, resolver: KotlinResolver,
): List<Diagnostic> {
    val params = lambda.valueParameters
    if (params.isEmpty()) return emptyList()
    resolver.enclosingCallAndParamIndex(lambda)?.let { (call, argIndex) ->
        val targets = runCatching { resolver.callTargets(call) }.getOrDefault(emptyList())
            .distinctBy { t ->
                val at = ((t.declaration() as? KotlinDomNode)?.psi)?.textRange?.startOffset
                if (t.origin.fromSource && at != null) Pair(t.origin.file?.toString(), at) else t
            }
        val sym = targets.singleOrNull() ?: return emptyList()
        // The slot must itself be a function type: one typed by a type variable (`fun <T : (C) -> Unit>
        // selectC(a: T, …)`) gets its shape from inference across the other arguments, which this does not follow.
        val raw = sym.paramTypes.getOrNull(resolver.lambdaParamIndex(call, argIndex, sym)) as? KotlinType ?: return emptyList()
        if (raw.isTypeParameter || raw.qualifiedName in sym.typeParameters ||
            !dev.ide.lang.kotlin.symbols.TypeRendering.isFunctionType(raw.qualifiedName)) return emptyList()
    }
    val shape = runCatching { resolver.expectedLambdaShape(lambda) }.getOrNull() ?: return emptyList()
    if (shape.isExtension || shape.receiverType != null) return emptyList()
    val n = shape.parameterTypes.size
    if (params.size <= n) return emptyList()
    return params.drop(n).filter { it.typeReference == null && it.destructuringDeclaration == null }.map { p ->
        val r = p.textRange
        Diagnostic(
            TextRange(r.startOffset, r.endOffset), Severity.ERROR,
            "Cannot infer type for value parameter '${p.name}': the expected function type takes $n parameter${if (n == 1) "" else "s"}",
            KotlinDiagnosticCodes.CANNOT_INFER_TYPE,
        )
    }
}
