package dev.ide.lang.kotlin

import dev.ide.kotlin.syntax.psi.KtCallableDeclaration
import dev.ide.kotlin.syntax.psi.KtClass
import dev.ide.kotlin.syntax.psi.KtClassOrObject
import dev.ide.kotlin.syntax.psi.KtDelegatedSuperTypeEntry
import dev.ide.kotlin.syntax.psi.KtEnumEntry
import dev.ide.kotlin.syntax.psi.KtNamedFunction
import dev.ide.kotlin.syntax.psi.KtObjectDeclaration
import dev.ide.kotlin.syntax.psi.KtParameter
import dev.ide.kotlin.syntax.psi.KtProperty
import dev.ide.kotlin.syntax.psi.KtTokens
import dev.ide.lang.dom.Diagnostic
import dev.ide.lang.dom.Severity
import dev.ide.lang.dom.TextRange
import dev.ide.lang.kotlin.parse.KotlinDomNode
import dev.ide.lang.kotlin.resolve.KotlinResolver

/**
 * MANY_IMPL_MEMBER_NOT_IMPLEMENTED and MANY_INTERFACES_MEMBER_NOT_IMPLEMENTED, from `FirNotImplementedOverrideChecker`
 * and `getImplementationStatus`/`subjectToManyNotImplemented` in `FirHelpers.kt`: a member that reaches a class from
 * more than one supertype, where the supertypes disagree (two implementations, or an interface's implementation
 * beside another interface's abstract declaration), must be overridden, since neither one wins. Two `by` delegates
 * that both supply it are the same defect.
 *
 * Judged over project source only, the way the compiler's intersection scope sees it: each direct supertype
 * contributes its most derived declaration of the member, a declaration that another contributor overrides is
 * dropped (subsumed), and a member reached twice through a shared base counts once. Members match on name and
 * written parameter types. A hierarchy with a generic class, a library supertype other than `Any`, or anything
 * unresolved is left alone, as is a class that declares any member of that name itself.
 */
internal fun KotlinSemanticChecks.manyImplementationsNotOverridden(cls: KtClassOrObject, resolver: KotlinResolver): List<Diagnostic> {
    if (cls is KtEnumEntry || (cls is KtObjectDeclaration && cls.isObjectLiteral())) return emptyList()
    if (cls is KtClass && (cls.isEnum() || cls.isAnnotation())) return emptyList()
    if (cls.hasModifier(KtTokens.EXPECT_KEYWORD) || cls.superTypeListEntries.size < 2) return emptyList()
    val canHaveAbstract = cls is KtClass && (cls.isInterface() || cls.hasModifier(KtTokens.ABSTRACT_KEYWORD) || cls.hasModifier(KtTokens.SEALED_KEYWORD))
    val supers = ArrayList<Pair<KtClassOrObject, Boolean>>()
    for (entry in cls.superTypeListEntries) {
        val typeRef = entry.typeReference ?: return emptyList()
        val fqn = classifierFqnOf(typeRef, resolver) ?: return emptyList()
        if (fqn == "kotlin.Any") continue
        val psi = sourceClassPsi(fqn) ?: return emptyList()
        supers += psi to (entry is KtDelegatedSuperTypeEntry)
    }
    val closures = HashMap<KtClassOrObject, List<KtClassOrObject>>()
    for ((s, _) in supers) closures[s] = closureOf(s, resolver) ?: return emptyList()
    val ownNames = cls.declarations.filterIsInstance<KtCallableDeclaration>().mapNotNullTo(HashSet()) { it.name } +
        cls.primaryConstructorParameters.filter { it.hasValOrVar() }.mapNotNull { it.name }
    val keys = LinkedHashSet<String>()
    for ((s, _) in supers) for (c in closures.getValue(s)) keys += memberKeys(c).keys
    val out = ArrayList<Diagnostic>()
    for (key in keys) {
        val name = key.substringAfter(':').substringBefore('(').substringAfterLast('.')
        if (name in ownNames) continue
        val contributions = ArrayList<Contribution>()
        for ((s, delegated) in supers) {
            if (delegated) {
                if (closures.getValue(s).any { key in memberKeys(it) }) contributions += Contribution(null, null, abstract = false, fromInterface = false, delegated = true)
            } else {
                contributions += effectiveDeclarations(s, key, resolver)
            }
        }
        val distinct = contributions.filter { it.delegated } +
            contributions.filter { !it.delegated }.distinctBy { it.declaration }
        val kept = distinct.filter { c ->
            c.owner == null || distinct.none { o -> o !== c && o.owner != null && o.owner !== c.owner && c.owner in (closureOf(o.owner, resolver) ?: emptyList()) }
        }
        if (kept.size < 2) continue
        // A member a `by` delegate supplies is the delegated one, not an intersection: only two delegates clash.
        if (kept.any { it.delegated }) {
            if (kept.count { it.delegated } > 1) {
                out += report(cls, "Class '${cls.name}' must override '$name' because it inherits multiple implementations of it", KotlinDiagnosticCodes.MANY_IMPL_MEMBER_NOT_IMPLEMENTED)
            }
            continue
        }
        val nonAbstract = kept.filter { !it.abstract }
        val nonAbstractInInterface = nonAbstract.count { it.fromInterface }
        val abstractInInterface = kept.count { it.abstract && it.fromInterface }
        val ambiguous = nonAbstract.size > 1 || (nonAbstractInInterface > 0 && abstractInInterface > 0)
        if (!ambiguous) continue
        if (nonAbstract.any { !it.fromInterface }) {
            out += report(cls, "Class '${cls.name}' must override '$name' because it inherits multiple implementations of it", KotlinDiagnosticCodes.MANY_IMPL_MEMBER_NOT_IMPLEMENTED)
        } else {
            if (canHaveAbstract && kept.any { it.abstract && !it.fromInterface }) continue
            out += report(cls, "Class '${cls.name}' must override '$name' because it inherits multiple interface methods of it", KotlinDiagnosticCodes.MANY_INTERFACES_MEMBER_NOT_IMPLEMENTED)
        }
    }
    return out
}

private class Contribution(
    val declaration: dev.ide.kotlin.syntax.psi.KtDeclaration?,
    val owner: KtClassOrObject?,
    val abstract: Boolean,
    val fromInterface: Boolean,
    val delegated: Boolean,
)

private fun report(cls: KtClassOrObject, message: String, code: String): Diagnostic {
    val r = (cls.nameIdentifier ?: cls).textRange
    return Diagnostic(TextRange(r.startOffset, r.endOffset), Severity.ERROR, message, code)
}

private fun KotlinSemanticChecks.sourceClassPsi(fqn: String): KtClassOrObject? =
    (service.sourceClass(fqn)?.node as? KotlinDomNode)?.psi as? KtClassOrObject

/** [cls] and every class above it, or null when the hierarchy holds anything this check does not judge. */
private fun KotlinSemanticChecks.closureOf(cls: KtClassOrObject, resolver: KotlinResolver): List<KtClassOrObject>? {
    val out = ArrayList<KtClassOrObject>()
    fun visit(c: KtClassOrObject): Boolean {
        if (c in out) return true
        if (c.typeParameters.isNotEmpty()) return false
        out += c
        for (entry in c.superTypeListEntries) {
            val typeRef = entry.typeReference ?: return false
            if (entry is KtDelegatedSuperTypeEntry) return false
            val fqn = classifierFqnOf(typeRef, resolver) ?: return false
            if (fqn == "kotlin.Any") continue
            val psi = sourceClassPsi(fqn) ?: return false
            if (!visit(psi)) return false
        }
        return true
    }
    return if (visit(cls)) out else null
}

/** The members [cls] declares itself, by key: `fun:name(parameter types)` or `val:name`; private ones are not
 *  inherited and are left out. */
private fun memberKeys(cls: KtClassOrObject): Map<String, dev.ide.kotlin.syntax.psi.KtDeclaration> {
    val out = LinkedHashMap<String, dev.ide.kotlin.syntax.psi.KtDeclaration>()
    for (d in cls.declarations) {
        if (d.hasModifier(KtTokens.PRIVATE_KEYWORD)) continue
        when (d) {
            is KtNamedFunction -> {
                val name = d.name ?: continue
                if (d.modifierList?.text?.contains("context") == true) continue
                val receiver = d.receiverTypeReference?.text?.filterNot { it.isWhitespace() }.orEmpty()
                val params = d.valueParameters.joinToString(",") { it.typeReference?.text?.filterNot { c -> c.isWhitespace() } ?: "?" }
                out["fun:$receiver.$name($params)".replace("fun:.", "fun:")] = d
            }
            is KtProperty -> {
                val name = d.name ?: continue
                val receiver = d.receiverTypeReference?.text?.filterNot { it.isWhitespace() }.orEmpty()
                out["val:$receiver.$name".replace("val:.", "val:")] = d
            }
        }
    }
    for (p in cls.primaryConstructorParameters) {
        if (!p.hasValOrVar() || p.hasModifier(KtTokens.PRIVATE_KEYWORD)) continue
        p.name?.let { out["val:$it"] = p }
    }
    return out
}

private fun isAbstractMember(d: dev.ide.kotlin.syntax.psi.KtDeclaration, owner: KtClassOrObject): Boolean {
    if (d.hasModifier(KtTokens.ABSTRACT_KEYWORD)) return true
    if (owner !is KtClass || !owner.isInterface()) return false
    return when (d) {
        is KtNamedFunction -> !d.hasBody()
        is KtProperty -> d.initializer == null && d.accessors.none { it.hasBody() }
        else -> false
    }
}

/** The most derived declarations of [key] reached through [cls]: its own, or else those of its supertypes. */
private fun KotlinSemanticChecks.effectiveDeclarations(
    cls: KtClassOrObject, key: String, resolver: KotlinResolver, visited: MutableSet<KtClassOrObject> = HashSet(),
): List<Contribution> {
    // A cyclic hierarchy is its own error; walk each class once.
    if (!visited.add(cls)) return emptyList()
    memberKeys(cls)[key]?.let { d ->
        return listOf(Contribution(d, cls, isAbstractMember(d, cls), cls is KtClass && cls.isInterface(), delegated = false))
    }
    val out = ArrayList<Contribution>()
    for (entry in cls.superTypeListEntries) {
        val fqn = entry.typeReference?.let { classifierFqnOf(it, resolver) } ?: continue
        val psi = sourceClassPsi(fqn) ?: continue
        out += effectiveDeclarations(psi, key, resolver, visited)
    }
    return out
}
