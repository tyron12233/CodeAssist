package dev.ide.lang.kotlin

import dev.ide.kotlin.syntax.psi.KtClass
import dev.ide.kotlin.syntax.psi.KtClassOrObject
import dev.ide.kotlin.syntax.psi.KtConstantExpression
import dev.ide.kotlin.syntax.psi.KtConstructorDelegationCall
import dev.ide.kotlin.syntax.psi.KtElement
import dev.ide.kotlin.syntax.psi.KtExpression
import dev.ide.kotlin.syntax.psi.KtNameReferenceExpression
import dev.ide.kotlin.syntax.psi.KtParameter
import dev.ide.kotlin.syntax.psi.KtProjectionKind
import dev.ide.kotlin.syntax.psi.KtSecondaryConstructor
import dev.ide.kotlin.syntax.psi.KtStringTemplateExpression
import dev.ide.kotlin.syntax.psi.KtSuperTypeCallEntry
import dev.ide.kotlin.syntax.psi.KtSuperTypeListEntry
import dev.ide.kotlin.syntax.psi.KtTypeReference
import dev.ide.kotlin.syntax.psi.KtUserType
import dev.ide.kotlin.syntax.psi.KtValueArgument
import dev.ide.lang.dom.Diagnostic
import dev.ide.lang.dom.Severity
import dev.ide.lang.dom.TextRange
import dev.ide.lang.kotlin.analysis.KotlinChecker
import dev.ide.lang.kotlin.parse.KotlinDomNode
import dev.ide.lang.kotlin.parse.unwrapParen
import dev.ide.lang.kotlin.resolve.KotlinResolver
import dev.ide.lang.kotlin.resolve.canonicalTypeForCheck
import dev.ide.lang.kotlin.resolve.enclosingClassFqnOf
import dev.ide.lang.kotlin.symbols.KotlinType

/*
 * The arguments of a constructor DELEGATION call checked against the constructors it can reach, as K2's call
 * resolution does for `this(...)`/`super(...)` in a secondary constructor and for `Base(...)` in a supertype list:
 * ARGUMENT_TYPE_MISMATCH against the one candidate, NONE_APPLICABLE when several take the arguments' count but
 * none their types, OVERLOAD_RESOLUTION_AMBIGUITY between constructors with identical signatures.
 *
 * A delegation call does not infer the class's type arguments: `this(...)` passes the class's own type parameters
 * and `super(...)` the ones its supertype entry writes, so every parameter type is known once substituted. Judged
 * only over a same-file class, and only for arguments whose type is read off the tree (a literal, or a parameter or
 * `val` with a written type); anything else makes a candidate undecided and the call is left alone.
 */

internal fun KotlinSemanticChecks.delegationCallCheckers(): List<KotlinChecker> = listOf(
    KotlinChecker(KtConstructorDelegationCall::class) { psi ->
        KotlinPerf.span("sem.ported.delegationCall") {
            if (resolveReady) reportAll(DelegationCall(this@delegationCallCheckers, resolver).secondary(psi as KtConstructorDelegationCall))
        }
    },
    KotlinChecker(KtSuperTypeCallEntry::class) { psi ->
        KotlinPerf.span("sem.ported.delegationCall") {
            if (resolveReady) reportAll(DelegationCall(this@delegationCallCheckers, resolver).supertype(psi as KtSuperTypeCallEntry))
        }
    },
)

private class DelegationCall(val checks: KotlinSemanticChecks, val resolver: KotlinResolver) {
    private val service = checks.service

    /** An argument's type: known, an unsuffixed integer literal (fits any integer type), `null`, or unknown. */
    private sealed interface ArgType {
        class Known(val type: KotlinType) : ArgType
        object IntLiteral : ArgType
        object NullLiteral : ArgType
        object Unknown : ArgType
    }

    private sealed interface Verdict {
        object Applicable : Verdict
        object Arity : Verdict
        object Undecided : Verdict
        class Mismatch(val bad: List<Pair<KtExpression, KotlinType>>) : Verdict
    }

    fun secondary(call: KtConstructorDelegationCall): List<Diagnostic> {
        if (call.isImplicit) return emptyList()
        val ctor = call.parent as? KtSecondaryConstructor ?: return emptyList()
        val cls = ctor.parents().firstOrNull { it is KtClassOrObject } as? KtClassOrObject ?: return emptyList()
        if (cls !is KtClass || cls.isEnum() || cls.isAnnotation()) return emptyList()
        val anchor = call.calleeExpression ?: return emptyList()
        return if (call.isCallToThis) {
            val own = cls.typeParameters.mapNotNull { it.name }.associateWith { KotlinType(it, isTypeParameter = true) }
            judge(cls, cls, own, call.valueArguments, anchor)
        } else {
            if (cls.primaryConstructor != null) return emptyList()
            val entry = cls.superTypeListEntries.singleOrNull { it !is KtSuperTypeCallEntry && !isInterfaceEntry(it, cls) } ?: return emptyList()
            val (target, subst) = superTarget(entry, cls) ?: return emptyList()
            judge(cls, target, subst, call.valueArguments, anchor)
        }
    }

    fun supertype(entry: KtSuperTypeCallEntry): List<Diagnostic> {
        val cls = entry.parents().firstOrNull { it is KtClassOrObject } as? KtClassOrObject ?: return emptyList()
        if (cls is dev.ide.kotlin.syntax.psi.KtEnumEntry) return emptyList()
        val (target, subst) = superTarget(entry, cls) ?: return emptyList()
        val anchor = entry.typeReference ?: return emptyList()
        return judge(cls, target, subst, entry.valueArguments, anchor)
    }

    private fun isInterfaceEntry(entry: KtSuperTypeListEntry, cls: KtClassOrObject): Boolean {
        val fqn = entry.typeReference?.let { checks.classifierFqnOf(it, resolver) } ?: return false
        return service.isInterfaceType(fqn) != false
    }

    /** The same-file class [entry] names and its type parameters bound to the entry's type arguments. */
    private fun superTarget(entry: KtSuperTypeListEntry, cls: KtClassOrObject): Pair<KtClass, Map<String, KotlinType>>? {
        val typeRef = entry.typeReference ?: return null
        val user = typeRef.typeElement as? KtUserType ?: return null
        val fqn = checks.classifierFqnOf(typeRef, resolver) ?: return null
        val target = (service.sourceClass(fqn)?.node as? KotlinDomNode)?.psi as? KtClass ?: return null
        if (target.containingFile !== cls.containingFile || user.referencedName != target.name) return null
        if (target.isEnum() || target.isAnnotation() || target.isInterface()) return null
        val params = target.typeParameters.mapNotNull { it.name }
        if (params.size != target.typeParameters.size || user.typeArguments.size != params.size) return null
        val subst = HashMap<String, KotlinType>()
        for ((i, a) in user.typeArguments.withIndex()) {
            if (a.projectionKind != KtProjectionKind.NONE) return null
            subst[params[i]] = typeIn(a.typeReference, cls) ?: return null
        }
        return target to subst
    }

    private fun judge(
        cls: KtClassOrObject, target: KtClass, subst: Map<String, KotlinType>, args: List<KtValueArgument>,
        anchor: KtElement,
    ): List<Diagnostic> {
        if (args.any { it.getSpreadElement() != null || it.getArgumentName() != null }) return emptyList()
        val candidates = ArrayList<List<KtParameter>>()
        target.primaryConstructor?.let { if (!hidden(it)) candidates += it.valueParameters }
        target.secondaryConstructors.forEach { if (!hidden(it)) candidates += it.valueParameters }
        if (target.primaryConstructor == null && target.secondaryConstructors.isEmpty()) candidates += emptyList<KtParameter>()
        if (candidates.isEmpty() || candidates.any { ps -> ps.any { it.isVarArg } }) return emptyList()
        val typing = KotlinStrictSubtyping(service) { name -> if (unboundedTypeParameter(cls, name)) UNBOUNDED_TYPE_PARAMETER else null }
        val argTypes = args.map { argType(it.getArgumentExpression(), cls) }
        val verdicts = candidates.map { ps -> verdict(ps, args, argTypes, target, subst, typing) }
        if (verdicts.any { it == Verdict.Undecided }) return emptyList()
        val applicable = candidates.indices.filter { verdicts[it] == Verdict.Applicable }
        if (applicable.size > 1) {
            // Specificity compares only the parameters the arguments fill; a tie that no candidate breaks by
            // needing fewer default values is ambiguous.
            val shapes = applicable.map { i -> candidates[i].take(args.size).map { p -> paramType(p, target, subst)?.let(::render) } }
            if (shapes.any { s -> s.any { it == null } } || shapes.distinct().size != 1) return emptyList()
            // The ones that use no default value win; with exactly one of those, the call resolves.
            if (applicable.count { candidates[it].size == args.size } == 1) return emptyList()
            return listOf(diagnostic(anchor, "Overload resolution ambiguity between constructors that fit these arguments equally", KotlinDiagnosticCodes.OVERLOAD_AMBIGUITY))
        }
        if (applicable.isNotEmpty()) return emptyList()
        val mismatched = verdicts.filterIsInstance<Verdict.Mismatch>()
        if (mismatched.isEmpty()) return emptyList()
        if (candidates.size == 1) {
            return mismatched.single().bad.map { (arg, expected) ->
                diagnostic(arg, "Argument type mismatch: ${renderShown(arg, cls)} does not fit ${TypeRenderingShim.render(expected)}", KotlinDiagnosticCodes.TYPE_MISMATCH)
            }
        }
        return listOf(diagnostic(anchor, "None of the constructors of '${target.name}' can be called with these arguments", KotlinDiagnosticCodes.CONSTRUCTOR_ARGS))
    }

    private fun verdict(
        ps: List<KtParameter>, args: List<KtValueArgument>, argTypes: List<ArgType>, target: KtClass,
        subst: Map<String, KotlinType>, typing: KotlinStrictSubtyping,
    ): Verdict {
        if (args.size > ps.size) return Verdict.Arity
        if (ps.drop(args.size).any { !it.hasDefaultValue() }) return Verdict.Arity
        val bad = ArrayList<Pair<KtExpression, KotlinType>>()
        var undecided = false
        for (i in args.indices) {
            val expr = args[i].getArgumentExpression() ?: return Verdict.Undecided
            val p = paramType(ps[i], target, subst) ?: return Verdict.Undecided
            val fits: Boolean? = when (val a = argTypes[i]) {
                ArgType.Unknown -> null
                ArgType.NullLiteral -> if (p.isTypeParameter) null else p.nullable
                ArgType.IntLiteral -> {
                    val pn = canonicalTypeForCheck(p).qualifiedName
                    if (!p.isTypeParameter && pn in INTEGER_LITERAL_TYPES) true
                    else typing.subtype(KotlinType("kotlin.Int"), p)
                }
                is ArgType.Known -> typing.subtype(a.type, p)
            }
            when (fits) {
                null -> undecided = true
                false -> bad += expr to p
                true -> {}
            }
        }
        return when {
            bad.isNotEmpty() -> Verdict.Mismatch(bad)
            undecided -> Verdict.Undecided
            else -> Verdict.Applicable
        }
    }

    private fun hidden(d: dev.ide.kotlin.syntax.psi.KtDeclaration): Boolean =
        d.annotationEntries.any { it.text.contains("Deprecated") && it.text.contains("HIDDEN") }

    private fun paramType(p: KtParameter, target: KtClass, subst: Map<String, KotlinType>): KotlinType? {
        val t = typeIn(p.typeReference, target) ?: return null
        return substituteKeepingNullability(t, subst)
    }

    /** [ref]'s written type, the type parameters of the classes around [at] marked as such. */
    private fun typeIn(ref: KtTypeReference?, at: KtClassOrObject): KotlinType? {
        val text = ref?.text ?: return null
        if ('(' in text || '&' in text || '@' in text) return null
        val parsed = service.typeFromText(text, resolver.fileContext, enclosingClassFqnOf(ref)) ?: return null
        val marked = service.markDeclaredTypeParameters(parsed, typeParameterNames(at)) ?: return null
        return marked.takeIf { known(it) }
    }

    private fun typeParameterNames(at: KtClassOrObject): Set<String> {
        val out = HashSet<String>()
        var c: KtElement? = at
        while (c != null) {
            if (c is KtClassOrObject) {
                c.typeParameters.mapNotNullTo(out) { it.name }
                // A class that is not `inner` does not see the type parameters around it.
                if (!(c is KtClass && c.isInner())) break
            }
            c = c.parent as? KtElement
        }
        return out
    }

    private fun known(t: KotlinType): Boolean {
        if (!t.isTypeParameter && !service.isKnownType(canonicalTypeForCheck(t).qualifiedName)) return false
        return t.typeArguments.all { (it as? KotlinType)?.let(::known) == true }
    }

    private fun argType(e: KtExpression?, cls: KtClassOrObject): ArgType {
        val u = e?.let { unwrapParen(it) } ?: return ArgType.Unknown
        return when (u) {
            is KtStringTemplateExpression -> ArgType.Known(KotlinType("kotlin.String"))
            is KtConstantExpression -> literal(u.text.trim())
            is KtNameReferenceExpression -> {
                val t = checks.writtenTypeOf(u, resolver) ?: return ArgType.Unknown
                // The written text lost its `?`: a nullable value is judged only where nullability cannot matter.
                val decl = checks.nearestLocalDecl(u.getReferencedName(), u.textRange.startOffset, u)
                val nullable = (decl as? KtParameter)?.typeReference?.text?.trim()?.endsWith("?") == true ||
                    (decl as? dev.ide.kotlin.syntax.psi.KtProperty)?.typeReference?.text?.trim()?.endsWith("?") == true
                val marked = service.markDeclaredTypeParameters(t, typeParameterNames(cls)) ?: return ArgType.Unknown
                if (!known(marked)) ArgType.Unknown else ArgType.Known(if (nullable) marked.withNullable(true) else marked)
            }
            else -> ArgType.Unknown
        }
    }

    private fun literal(text: String): ArgType = when {
        text == "null" -> ArgType.NullLiteral
        text == "true" || text == "false" -> ArgType.Known(KotlinType("kotlin.Boolean"))
        text.startsWith("'") -> ArgType.Known(KotlinType("kotlin.Char"))
        text.startsWith("0x", true) || text.startsWith("0b", true) ->
            if (text.endsWith("L")) ArgType.Known(KotlinType("kotlin.Long")) else if (text.endsWith("u", true) || text.endsWith("uL", true)) ArgType.Unknown else ArgType.IntLiteral
        text.endsWith("f", true) -> ArgType.Known(KotlinType("kotlin.Float"))
        text.endsWith("L") -> ArgType.Known(KotlinType("kotlin.Long"))
        text.endsWith("u", true) || text.endsWith("uL", true) -> ArgType.Unknown
        '.' in text || 'e' in text || 'E' in text -> ArgType.Known(KotlinType("kotlin.Double"))
        text.all { it.isDigit() || it == '_' } -> ArgType.IntLiteral
        else -> ArgType.Unknown
    }

    private fun unboundedTypeParameter(cls: KtClassOrObject, name: String): Boolean {
        var c: KtElement? = cls
        while (c != null) {
            if (c is KtClassOrObject) {
                val tp = c.typeParameters.firstOrNull { it.name == name }
                if (tp != null) {
                    if (tp.extendsBound != null) return false
                    return (c as? KtClass)?.typeConstraints?.none { it.subjectTypeParameterName?.getReferencedName() == name } ?: true
                }
            }
            c = c.parent as? KtElement
        }
        return false
    }

    private fun render(t: KotlinType): String = buildString {
        append(canonicalTypeForCheck(t).qualifiedName)
        if (t.typeArguments.isNotEmpty()) append(t.typeArguments.joinToString(",", "<", ">") { (it as? KotlinType)?.let(::render) ?: "?" })
        if (t.nullable) append('?')
    }

    private fun renderShown(arg: KtExpression, cls: KtClassOrObject): String = when (val a = argType(arg, cls)) {
        is ArgType.Known -> TypeRenderingShim.render(a.type)
        ArgType.IntLiteral -> "Int"
        ArgType.NullLiteral -> "Nothing?"
        ArgType.Unknown -> "'${arg.text}'"
    }

    private fun diagnostic(at: KtElement, message: String, code: String): Diagnostic {
        val r = at.textRange
        return Diagnostic(TextRange(r.startOffset, r.endOffset), Severity.ERROR, message, code)
    }

    private fun KtElement.parents(): Sequence<KtElement> = generateSequence(parent as? KtElement) { it.parent as? KtElement }
}

private val INTEGER_LITERAL_TYPES = setOf("kotlin.Int", "kotlin.Long", "kotlin.Short", "kotlin.Byte")

/** A type as a reader writes it: simple names, arguments, `?`. */
private object TypeRenderingShim {
    fun render(t: KotlinType): String = buildString {
        if (t.projection.isNotEmpty() && t.projection != "*") append(t.projection).append(' ')
        append(t.qualifiedName.substringAfterLast('.'))
        if (t.typeArguments.isNotEmpty()) append(t.typeArguments.joinToString(", ", "<", ">") { (it as? KotlinType)?.let(::render) ?: "*" })
        if (t.nullable) append('?')
    }
}
