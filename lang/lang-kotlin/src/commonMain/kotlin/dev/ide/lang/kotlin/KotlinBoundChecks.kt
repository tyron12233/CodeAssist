package dev.ide.lang.kotlin

import dev.ide.kotlin.syntax.psi.KtCallExpression
import dev.ide.kotlin.syntax.psi.KtClass
import dev.ide.kotlin.syntax.psi.KtClassOrObject
import dev.ide.kotlin.syntax.psi.KtConstantExpression
import dev.ide.kotlin.syntax.psi.KtElement
import dev.ide.kotlin.syntax.psi.KtExpression
import dev.ide.kotlin.syntax.psi.KtImportDirective
import dev.ide.kotlin.syntax.psi.KtLambdaArgument
import dev.ide.kotlin.syntax.psi.KtNameReferenceExpression
import dev.ide.kotlin.syntax.psi.KtNamedFunction
import dev.ide.kotlin.syntax.psi.KtPackageDirective
import dev.ide.kotlin.syntax.psi.KtParameter
import dev.ide.kotlin.syntax.psi.KtProjectionKind
import dev.ide.kotlin.syntax.psi.KtProperty
import dev.ide.kotlin.syntax.psi.KtQualifiedExpression
import dev.ide.kotlin.syntax.psi.KtStringTemplateExpression
import dev.ide.kotlin.syntax.psi.KtThisExpression
import dev.ide.kotlin.syntax.psi.KtTypeAlias
import dev.ide.kotlin.syntax.psi.KtTypeParameter
import dev.ide.kotlin.syntax.psi.KtTypeParameterListOwner
import dev.ide.kotlin.syntax.psi.KtTypeProjection
import dev.ide.kotlin.syntax.psi.KtTypeReference
import dev.ide.kotlin.syntax.psi.KtUserType
import dev.ide.lang.dom.Diagnostic
import dev.ide.lang.dom.Severity
import dev.ide.lang.dom.TextRange
import dev.ide.lang.kotlin.analysis.KotlinChecker
import dev.ide.lang.kotlin.parse.KotlinDomNode
import dev.ide.lang.kotlin.parse.unwrapParen
import dev.ide.lang.kotlin.resolve.KotlinResolver
import dev.ide.lang.kotlin.resolve.canonicalTypeForCheck
import dev.ide.lang.kotlin.resolve.enclosingClassFqnOf
import dev.ide.lang.kotlin.resolve.smartCastNonNull
import dev.ide.lang.kotlin.resolve.calleeFunctionOf
import dev.ide.lang.kotlin.resolve.callTargets
import dev.ide.lang.kotlin.symbols.KotlinType

/*
 * Upper bounds judged where a TYPE PARAMETER is involved, which the classifier-level bound check backs off from:
 *
 *  - UPPER_BOUND_VIOLATED (`FirUpperBoundViolatedChecker` / the call-site variant in `FirTypeArgumentsChecks`): an
 *    explicit type argument, in a call or a written type, that is not a subtype of its parameter's bound once the
 *    bounds of the type parameters in scope are taken into account (`A<F>` for `class A<T : CharSequence>` where
 *    `F : String?`, `foo<Inv<F>>()` for `<E : Inv<F?>>`).
 *  - CANNOT_INFER_PARAMETER_TYPE with ARGUMENT_TYPE_MISMATCH, as K2's constraint system reports a type variable whose
 *    lower bound (the argument) contradicts its declared upper bound: `foo1(x)` for `fun <E : CharSequence> foo1(x: E)`
 *    with `x: F`, `F : String?`; `N("1")` for `class N<T : Number>(t: T)`.
 *
 * Judged only over declarations in the same file, with types read off the tree (written type arguments, literals,
 * parameters and `val`s with written types), and the subtyping is [KotlinStrictSubtyping]: undecided means silent.
 */

internal fun KotlinSemanticChecks.boundCheckers(): List<KotlinChecker> = listOf(
    KotlinChecker(KtCallExpression::class) { psi ->
        KotlinPerf.span("sem.ported.bounds.call") {
            if (resolveReady) reportAll(BoundChecks(this@boundCheckers, resolver).call(psi as KtCallExpression))
        }
    },
    KotlinChecker(KtUserType::class) { psi ->
        KotlinPerf.span("sem.ported.bounds.type") {
            if (resolveReady) reportAll(BoundChecks(this@boundCheckers, resolver).userType(psi as KtUserType))
        }
    },
)

private class BoundChecks(val checks: KotlinSemanticChecks, val resolver: KotlinResolver) {
    private val service = checks.service

    /** A generic declaration's own type parameters and the written bound of each (null when it has none). */
    private class Generic(val owner: KtTypeParameterListOwner, val names: List<String>, val bounds: List<KtTypeReference?>)

    // --- explicit type arguments in a written type ----------------------------------------------------------------

    fun userType(userType: KtUserType): List<Diagnostic> {
        if (userType.parent is KtUserType) return emptyList()
        val args = userType.typeArguments
        if (args.isEmpty()) return emptyList()
        if (userType.parents().any { it is KtImportDirective || it is KtPackageDirective || it is KtTypeAlias }) return emptyList()
        val typeRef = userType.parents().firstOrNull { it is KtTypeReference } as? KtTypeReference ?: return emptyList()
        val fqn = checks.classifierFqnOf(typeRef, resolver) ?: return emptyList()
        val cls = sourceClass(fqn, userType) ?: return emptyList()
        if (userType.referencedName != cls.name) return emptyList() // a typealias
        val generic = genericOf(cls) ?: return emptyList()
        return judgeExplicit(generic, args, userType, allowOuter = false)
    }

    // --- calls ------------------------------------------------------------------------------------------------------

    fun call(call: KtCallExpression): List<Diagnostic> {
        if (call.valueArgumentList == null && call.lambdaArguments.isEmpty()) return emptyList()
        val (generic, params) = calleeGeneric(call) ?: return emptyList()
        val allowOuter = outerIdentity(call, generic.owner)
        val typeArgs = call.typeArgumentList?.arguments
        if (typeArgs != null) return judgeExplicit(generic, typeArgs, call, allowOuter)
        return judgeInferred(call, generic, params, allowOuter)
    }

    /** The source function or class a call reaches (its parameters too), when there is exactly one. */
    private fun calleeGeneric(call: KtCallExpression): Pair<Generic, List<KtParameter>>? {
        val callee = call.calleeExpression as? KtNameReferenceExpression ?: return null
        checks.constructedSourceClass(call, resolver)?.let { fqn ->
            val cls = sourceClass(fqn, call) ?: return null
            if (callee.getReferencedName() != cls.name &&
                checks.identityAliasTarget(call.containingKtFile, callee.getReferencedName()) != cls.name) return null
            val ctors = listOfNotNull(cls.primaryConstructor?.valueParameters) + cls.secondaryConstructors.map { it.valueParameters }
            val params = ctors.singleOrNull() ?: if (ctors.isEmpty() && call.valueArguments.isEmpty()) emptyList() else return null
            return (genericOf(cls) ?: return null) to params
        }
        // The same declaration can be listed twice (from the file and from the project index).
        val targets = runCatching { resolver.callTargets(call) }.getOrDefault(emptyList())
            .distinctBy { t ->
                val at = ((t.declaration() as? KotlinDomNode)?.psi)?.textRange?.startOffset
                if (t.origin.fromSource && at != null) Pair(t.origin.file?.toString(), at) else t
            }
        if (targets.size != 1) return null
        val sym = resolver.calleeFunctionOf(call) ?: return null
        if (!sym.origin.fromSource) return null
        val fn = (sym.declaration() as? KotlinDomNode)?.psi as? KtNamedFunction ?: return null
        if (fn.containingFile !== call.containingFile) return null
        return (genericOf(fn) ?: return null) to fn.valueParameters
    }

    private fun judgeExplicit(generic: Generic, args: List<KtTypeProjection>, at: KtElement, allowOuter: Boolean): List<Diagnostic> {
        if (args.size != generic.names.size) return emptyList()
        val scope = scopeBounds(at)
        val names = scopeNames(at)
        val written = args.map { a ->
            if (a.projectionKind != KtProjectionKind.NONE) null
            else a.typeReference?.let { parse(it, names) }
        }
        val subst = HashMap<String, KotlinType>()
        generic.names.forEachIndexed { i, n -> written[i]?.let { subst[n] = it } }
        val typing = KotlinStrictSubtyping(service, bounds = scope)
        val out = ArrayList<Diagnostic>()
        for (i in args.indices) {
            val arg = written[i] ?: continue
            val bound = boundOf(generic, i, subst, allowOuter) ?: continue
            if (!mentionsTypeParameter(arg) && !mentionsTypeParameter(bound)) continue // the classifier-level check's
            if (checks.boundViolated(arg, bound)) continue
            if (typing.subtype(arg, bound) != false) continue
            val ref = args[i].typeReference ?: continue
            out += diagnostic(
                ref, "Type argument is not within its bounds: should be a subtype of '${render(bound)}'",
                KotlinDiagnosticCodes.UPPER_BOUND_VIOLATED,
            )
        }
        return out
    }

    private fun judgeInferred(call: KtCallExpression, generic: Generic, params: List<KtParameter>, allowOuter: Boolean): List<Diagnostic> {
        val args = call.valueArguments
        if (args.any { it.getSpreadElement() != null || it.getArgumentName() != null || it is KtLambdaArgument }) return emptyList()
        if (params.any { it.isVarArg } || args.size > params.size) return emptyList()
        val scope = scopeBounds(call)
        val names = scopeNames(call)
        val typing = KotlinStrictSubtyping(service, bounds = scope)
        val bad = ArrayList<Diagnostic>()
        var violated = false
        for ((i, arg) in args.withIndex()) {
            val text = params[i].typeReference?.text?.trim() ?: continue
            val index = generic.names.indexOf(text)
            if (index < 0) continue
            val bound = boundOf(generic, index, emptyMap(), allowOuter) ?: continue
            if (generic.names.any { mentionsName(bound, it) }) continue
            val expr = arg.getArgumentExpression() ?: continue
            val actual = trustedType(expr, names) ?: continue
            if (typing.subtype(actual, bound) != false) continue
            violated = true
            // The applicability check already reports a concrete, non-null argument outside a concrete bound, for a
            // function; a constructor's arguments are not held against its class's bounds there.
            val reported = generic.owner !is dev.ide.kotlin.syntax.psi.KtClass &&
                !actual.nullable && !actual.isTypeParameter && !bound.isTypeParameter && checks.boundViolated(actual, bound)
            if (!reported) bad += checks.mismatchDiagnostic(expr.textRange.startOffset, expr.textRange.endOffset, actual, bound)
        }
        if (!violated) return emptyList()
        val callee = call.calleeExpression ?: return emptyList()
        val infer = diagnostic(
            callee, "Cannot infer a type for this type parameter: the argument is outside its bound",
            KotlinDiagnosticCodes.CANNOT_INFER_TYPE,
        )
        return listOf(infer) + bad
    }

    // --- declarations ---------------------------------------------------------------------------------------------

    private fun genericOf(owner: KtTypeParameterListOwner): Generic? {
        val tps = owner.typeParameters
        if (tps.isEmpty()) return null
        val names = tps.map { it.name ?: return null }
        val bounds = tps.map { tp ->
            val constraints = owner.typeConstraints.filter { it.subjectTypeParameterName?.getReferencedName() == tp.name }
            val all = listOfNotNull(tp.extendsBound) + constraints.mapNotNull { it.boundTypeReference }
            if (all.size > 1) return null
            all.singleOrNull()
        }
        return Generic(owner, names, bounds)
    }

    /** Bound [i] of [generic] with its own parameters replaced per [subst]; null when unbounded or unreadable, or
     *  when it names an outer type parameter the call cannot be sure is the same one. */
    private fun boundOf(generic: Generic, i: Int, subst: Map<String, KotlinType>, allowOuter: Boolean): KotlinType? {
        val ref = generic.bounds[i] ?: return null
        val names = scopeNames(ref)
        val parsed = parse(ref, names) ?: return null
        val own = generic.names.toSet()
        if (names.any { it !in own && mentionsName(parsed, it) } && !allowOuter) return null
        if (own.any { mentionsName(parsed, it) && it !in subst }) return null
        return substituteKeepingNullability(parsed, subst)
    }

    /**
     * Whether the type parameters of the class declaring [owner] mean the same at [call] as in [owner]'s bounds: the
     * call is inside that class and made on its own instance (no receiver, or `this`).
     */
    private fun outerIdentity(call: KtCallExpression, owner: KtTypeParameterListOwner): Boolean {
        val cls = (owner as? KtElement)?.parents()?.firstOrNull { it is KtClassOrObject } as? KtClassOrObject ?: return true
        if (call.parents().none { it === cls }) return false
        val q = call.parent as? KtQualifiedExpression
        if (q != null && q.selectorExpression === call) return q.receiverExpression is KtThisExpression &&
            (q.receiverExpression as KtThisExpression).getLabelName() == null
        // An inner class or object in between may bring its own receiver.
        return call.parents().takeWhile { it !== cls }.none { it is KtClassOrObject }
    }

    private fun sourceClass(fqn: String, from: KtElement): KtClass? {
        val cls = (service.sourceClass(fqn)?.node as? KotlinDomNode)?.psi as? KtClass ?: return null
        return cls.takeIf { it.containingFile === from.containingFile }
    }

    // --- types ------------------------------------------------------------------------------------------------------

    /** The type parameters visible at [at], innermost declaration first. */
    /** The declarations whose type parameters are visible at [at], innermost first: a class that is not `inner`
     *  hides those of the classes around it, and everything past it is left out. */
    private fun scopeOwners(at: KtElement): List<KtTypeParameterListOwner> {
        val out = ArrayList<KtTypeParameterListOwner>()
        for (e in sequenceOf(at) + at.parents()) {
            if (e is KtTypeParameterListOwner) out += e
            if (e is KtClassOrObject && !(e is KtClass && e.isInner())) break
        }
        return out
    }

    private fun scopeNames(at: KtElement): Set<String> =
        scopeOwners(at).flatMap { o -> o.typeParameters.mapNotNull { it.name } }.toSet()

    /** The bound of each type parameter in scope at [at] ([UNBOUNDED_TYPE_PARAMETER] for none), null if unreadable. */
    private fun scopeBounds(at: KtElement): (String) -> KotlinType? {
        val owners = scopeOwners(at)
        val names = scopeNames(at)
        val memo = HashMap<String, KotlinType?>()
        return { name ->
            memo.getOrPut(name) {
                val owner = owners.firstOrNull { o -> o.typeParameters.any { it.name == name } }
                val tp: KtTypeParameter? = owner?.typeParameters?.firstOrNull { it.name == name }
                if (owner == null || tp == null) null else {
                    val constraints = owner.typeConstraints.filter { it.subjectTypeParameterName?.getReferencedName() == name }
                    val all = listOfNotNull(tp.extendsBound) + constraints.mapNotNull { it.boundTypeReference }
                    when (all.size) {
                        0 -> UNBOUNDED_TYPE_PARAMETER
                        1 -> parse(all[0], names)
                        else -> null
                    }
                }
            }
        }
    }

    private fun parse(ref: KtTypeReference, names: Set<String>): KotlinType? {
        val text = ref.text
        if ('(' in text || '&' in text || '@' in text || '*' in text) return null
        val parsed = service.typeFromText(text, resolver.fileContext, enclosingClassFqnOf(ref)) ?: return null
        // A type parameter named like a class leaves the name ambiguous to this reading.
        if (names.any { n -> mentionsWord(text, n) && service.resolveTypeName(n, resolver.fileContext, enclosingClassFqnOf(ref))?.let { service.isKnownType(it) } == true }) return null
        val marked = service.markDeclaredTypeParameters(parsed, names) ?: return null
        return marked.takeIf { known(it) }
    }

    private fun mentionsWord(text: String, word: String): Boolean =
        Regex("(?<![A-Za-z0-9_.])" + Regex.escape(word) + "(?![A-Za-z0-9_])").containsMatchIn(text)

    /** Every classifier in [t] known, and none an inner class, whose type carries its outer class's arguments too. */
    private fun known(t: KotlinType): Boolean {
        if (!t.isTypeParameter) {
            val name = canonicalTypeForCheck(t).qualifiedName
            if (!service.isKnownType(name)) return false
            val cls = (service.sourceClass(name)?.node as? KotlinDomNode)?.psi as? KtClass
            if (cls != null && cls.isInner()) return false
        }
        return t.typeArguments.all { (it as? KotlinType)?.let(::known) == true }
    }

    /** A literal's type, or a local's / parameter's written type (non-null where a null check smart-casts it). */
    private fun trustedType(e: KtExpression, names: Set<String>): KotlinType? = when (val u = unwrapParen(e)) {
        is KtStringTemplateExpression -> KotlinType("kotlin.String")
        is KtConstantExpression -> when {
            u.text == "true" || u.text == "false" -> KotlinType("kotlin.Boolean")
            u.text.startsWith("'") -> KotlinType("kotlin.Char")
            else -> null
        }
        is KtNameReferenceExpression -> {
            val t = checks.writtenTypeOf(u, resolver)
            val decl = checks.nearestLocalDecl(u.getReferencedName(), u.textRange.startOffset, u)
            val text = (decl as? KtParameter)?.typeReference?.text ?: (decl as? KtProperty)?.typeReference?.text
            val nonNullByFlow = resolver.smartCastNonNull(u) || bangBangBefore(u)
            val nullable = text?.trim()?.endsWith("?") == true && !nonNullByFlow
            val marked = t?.let { service.markDeclaredTypeParameters(it, names) }?.takeIf { known(it) }
            // A type parameter's value known non-null is `T & Any`, which this reading has no form for.
            if (marked == null || (marked.isTypeParameter && nonNullByFlow)) null
            else if (nullable) marked.withNullable(true) else marked
        }
        // `Inv<F>()`: a same-file class constructed with written type arguments is exactly that type.
        is KtCallExpression -> {
            val written = u.typeArgumentList?.arguments
            val fqn = if (written == null) null else checks.constructedSourceClass(u, resolver)
            val cls = fqn?.let { sourceClass(it, u) }
            if (written == null || fqn == null || cls == null || cls.typeParameters.size != written.size) null
            else {
                val args = written.map { a -> if (a.projectionKind != KtProjectionKind.NONE) null else a.typeReference?.let { parse(it, names) } }
                if (args.any { it == null }) null else KotlinType(fqn, args.filterNotNull())
            }
        }
        else -> null
    }

    /** Whether `name!!` is written between [ref]'s declaration and [ref]: a smart cast the flow pass may not follow. */
    private fun bangBangBefore(ref: KtNameReferenceExpression): Boolean {
        val name = ref.getReferencedName()
        val decl = checks.nearestLocalDecl(name, ref.textRange.startOffset, ref) as? dev.ide.kotlin.syntax.psi.KtNamedDeclaration ?: return true
        val from = decl.nameIdentifier?.textRange?.endOffset ?: return true
        val before = ref.containingFile.sourceText.subSequence(from, ref.textRange.startOffset.coerceAtLeast(from))
        return Regex("(?<![A-Za-z0-9_`])" + Regex.escape(name) + "\\s*!!").containsMatchIn(before)
    }

    private fun mentionsTypeParameter(t: KotlinType): Boolean =
        t.isTypeParameter || t.typeArguments.any { (it as? KotlinType)?.let(::mentionsTypeParameter) == true }

    private fun mentionsName(t: KotlinType, name: String): Boolean =
        (t.isTypeParameter && t.qualifiedName == name) || t.typeArguments.any { (it as? KotlinType)?.let { a -> mentionsName(a, name) } == true }

    private fun render(t: KotlinType): String = buildString {
        if (t.projection.isNotEmpty() && t.projection != "*") append(t.projection).append(' ')
        append(t.qualifiedName.substringAfterLast('.'))
        if (t.typeArguments.isNotEmpty()) append(t.typeArguments.joinToString(", ", "<", ">") { (it as? KotlinType)?.let(::render) ?: "*" })
        if (t.nullable) append('?')
    }

    private fun diagnostic(at: KtElement, message: String, code: String): Diagnostic {
        val r = at.textRange
        return Diagnostic(TextRange(r.startOffset, r.endOffset), Severity.ERROR, message, code)
    }

    private fun KtElement.parents(): Sequence<KtElement> = generateSequence(parent as? KtElement) { it.parent as? KtElement }
}
