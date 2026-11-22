package dev.ide.lang.kotlin

import dev.ide.kotlin.syntax.psi.KtEnumEntry
import dev.ide.kotlin.syntax.psi.KtCallExpression
import dev.ide.kotlin.syntax.psi.KtCallableDeclaration
import dev.ide.kotlin.syntax.psi.KtNamedDeclaration
import dev.ide.kotlin.syntax.psi.KtClass
import dev.ide.kotlin.syntax.psi.KtClassOrObject
import dev.ide.kotlin.syntax.psi.KtDelegatedSuperTypeEntry
import dev.ide.kotlin.syntax.psi.KtElement
import dev.ide.kotlin.syntax.psi.KtFile
import dev.ide.kotlin.syntax.psi.KtNameReferenceExpression
import dev.ide.kotlin.syntax.psi.KtNamedFunction
import dev.ide.kotlin.syntax.psi.KtProperty
import dev.ide.kotlin.syntax.psi.KtQualifiedExpression
import dev.ide.kotlin.syntax.psi.KtSuperExpression
import dev.ide.kotlin.syntax.psi.KtTokens
import dev.ide.lang.dom.Diagnostic
import dev.ide.lang.dom.Severity
import dev.ide.lang.dom.TextRange
import dev.ide.lang.kotlin.parse.KotlinDomNode
import dev.ide.lang.kotlin.resolve.KotlinResolver
import dev.ide.lang.kotlin.resolve.enclosingClassFqnOf
import dev.ide.lang.kotlin.symbols.FileContext
import dev.ide.lang.resolve.Modifier
import dev.ide.lang.resolve.SymbolKind

/**
 * Member access through `super`:
 *
 *  - AMBIGUOUS_SUPER, ported from `findTypesForSuperCandidates` (`SuperCalls.kt`) and the body resolver's
 *    `transformSuperReceiver`: an unqualified `super.m` picks the supertypes that declare `m`, preferring those
 *    with a concrete `m`; more than one left is ambiguous.
 *  - ABSTRACT_SUPER_CALL, ported from `FirAbstractSuperCallChecker`: the member `super.m` (or `super<T>.m`)
 *    resolves to is abstract, so there is no implementation to call.
 *
 * The members come from each supertype's own and inherited members. Where the index lists an overridden member
 * beside its override, a supertype counts as concrete when ANY member of the name is, so an abstract verdict is
 * only reached when every member of that name is abstract. A supertype that does not resolve, a delegated one,
 * a labeled `super`, and the methods of `Any` (resolved by rules of their own) all back off.
 */
internal object KotlinSuperCallChecks {

    private val METHODS_OF_ANY = setOf("equals", "hashCode", "toString")

    fun check(checks: KotlinSemanticChecks, e: KtSuperExpression, resolver: KotlinResolver): Diagnostic? {
        if (e.getLabelName() != null) return null
        val q = e.parent as? KtQualifiedExpression ?: return null
        if (q.receiverExpressionOrNull !== e) return null
        val (nameRef, isCall) = when (val sel = q.selectorExpression) {
            is KtNameReferenceExpression -> sel to false
            is KtCallExpression -> (sel.calleeExpression as? KtNameReferenceExpression ?: return null) to true
            else -> return null
        }
        val name = nameRef.getReferencedName()
        if (name in METHODS_OF_ANY) return null
        val cls = enclosingClass(e) ?: return null
        val service = checks.service
        val qualifier = e.superTypeQualifier
        val types: List<String> = if (qualifier != null) {
            listOf(resolve(checks, qualifier.text, resolver.fileContext, cls) ?: return null)
        } else if (cls is KtEnumEntry) {
            // An enum entry's only supertype is its enum class, which it never writes down.
            val enumCls = (cls.parent?.parent as? KtClass)?.takeIf { it.isEnum() } ?: return null
            listOf(enclosingClassFqnOf(enumCls) ?: return null)
        } else {
            val entries = cls.superTypeListEntries
            if (entries.isEmpty() || entries.any { it is KtDelegatedSuperTypeEntry }) return null
            val supertypes = entries.map { entry -> resolve(checks, entry.typeReference?.text ?: return null, resolver.fileContext, cls) ?: return null }
            candidates(checks, supertypes, name, isCall) ?: return null
        }
        if (types.size >= 2) {
            val r = e.textRange
            return Diagnostic(
                TextRange(r.startOffset, r.endOffset), Severity.ERROR,
                "Many supertypes available, please specify the one you mean in angle brackets, e.g. 'super<Foo>'",
                KotlinDiagnosticCodes.SUPER_MISUSE,
            )
        }
        if (state(checks, types.singleOrNull() ?: return null, name, isCall, HashSet()) != State.ABSTRACT) return null
        val r = nameRef.textRange
        return Diagnostic(
            TextRange(r.startOffset, r.endOffset), Severity.ERROR,
            "Abstract member cannot be accessed directly", KotlinDiagnosticCodes.SUPER_MISUSE,
        )
    }

    private enum class State { ABSENT, CONCRETE, ABSTRACT }

    /** `resolveSupertypesByMembers` with non-concrete interface members allowed (a named member). */
    private fun candidates(checks: KotlinSemanticChecks, supertypes: List<String>, name: String, isCall: Boolean): List<String>? {
        val concrete = ArrayList<String>()
        val nonConcrete = ArrayList<String>()
        for (s in supertypes) {
            when (state(checks, s, name, isCall, HashSet()) ?: return null) {
                State.CONCRETE -> concrete += s
                State.ABSTRACT -> nonConcrete += s
                State.ABSENT -> {}
            }
        }
        // A concrete member in a supertype of one whose member is abstract is overridden by that abstract one.
        concrete.removeAll { c -> nonConcrete.any { n -> checks.service.typeByFqn(c).isAssignableFrom(checks.service.typeByFqn(n)) } }
        return concrete.ifEmpty { nonConcrete }
    }

    /**
     * Whether [fqn]'s member [name] (as seen from a subclass) is concrete, abstract or absent; null when that is
     * not certain. A project class answers by its own declaration of the name when it has one (the most derived
     * member wins), and otherwise by its supertypes; a class that delegates a supertype (`: A by a`) has members
     * this does not see. A library class answers from its listed members only when they all agree.
     */
    private fun state(checks: KotlinSemanticChecks, fqn: String, name: String, isCall: Boolean, seen: MutableSet<String>): State? {
        val service = checks.service
        if (!seen.add(fqn)) return null
        if (!service.isKnownType(fqn) || !service.supertypeClosureKnown(fqn)) return null
        val cls = (service.sourceClass(fqn)?.node as? KotlinDomNode)?.psi as? KtClassOrObject
        if (cls == null) {
            val members = service.ownAndInheritedMembers(fqn).filter {
                it.name == name && (it.kind == SymbolKind.FIELD || isCall && it.kind == SymbolKind.METHOD)
            }
            return when {
                members.isEmpty() -> State.ABSENT
                members.all { Modifier.ABSTRACT in it.modifiers } -> State.ABSTRACT
                members.none { Modifier.ABSTRACT in it.modifiers } -> State.CONCRETE
                else -> null
            }
        }
        if (cls.superTypeListEntries.any { it is KtDelegatedSuperTypeEntry }) return null
        val isInterface = (cls as? KtClass)?.isInterface() == true
        val own: List<KtNamedDeclaration> =
            cls.declarations.filterIsInstance<KtCallableDeclaration>().filter { (it is KtProperty || isCall && it is KtNamedFunction) && it.name == name } +
                (cls as? KtClass)?.primaryConstructorParameters.orEmpty().filter { it.hasValOrVar() && it.name == name }
        if (own.isNotEmpty()) {
            fun abstract(d: KtNamedDeclaration) = d.hasModifier(KtTokens.ABSTRACT_KEYWORD) || isInterface && when (d) {
                is KtNamedFunction -> !d.hasBody()
                is KtProperty -> d.initializer == null && d.getter == null && !d.hasDelegate()
                else -> false
            }
            return when {
                own.all(::abstract) -> State.ABSTRACT
                own.none(::abstract) -> State.CONCRETE
                else -> null
            }
        }
        var result = State.ABSENT
        for (entry in cls.superTypeListEntries) {
            val superFqn = resolve(checks, entry.typeReference?.text ?: return null, service.sourceClass(fqn)?.ctx, cls) ?: return null
            when (state(checks, superFqn, name, isCall, seen) ?: return null) {
                State.ABSENT -> {}
                State.CONCRETE -> if (result == State.ABSTRACT) return null else result = State.CONCRETE
                State.ABSTRACT -> if (result == State.CONCRETE) return null else result = State.ABSTRACT
            }
        }
        return result
    }

    /** A supertype written on [cls], resolved in the scope of the file that declares [cls]. */
    private fun resolve(checks: KotlinSemanticChecks, text: String, ctx: FileContext?, cls: KtClassOrObject): String? =
        checks.service.resolveTypeName(text.substringBefore('<').trim(), ctx, enclosingClassFqnOf(cls))

    private fun enclosingClass(e: KtElement): KtClassOrObject? {
        var p = e.parent
        while (p != null && p !is KtFile) {
            if (p is KtClassOrObject) return p
            p = p.parent
        }
        return null
    }
}
