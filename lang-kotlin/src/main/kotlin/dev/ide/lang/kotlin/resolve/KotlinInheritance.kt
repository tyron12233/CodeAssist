package dev.ide.lang.kotlin.resolve

import dev.ide.lang.kotlin.symbols.KotlinSymbol
import dev.ide.lang.kotlin.symbols.KotlinSymbolService
import dev.ide.lang.resolve.Modifier
import dev.ide.lang.resolve.SymbolKind
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtCallableDeclaration
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtDelegatedSuperTypeEntry
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtProperty

/** Inheritance analysis: supertype member closure, overridable members, and unimplemented-abstract / hidden-member reports. */

/** The inherited members overridable at [offset]: every non-final/non-private/non-static member of the
 *  enclosing class's declared supertypes, minus those it already declares. Empty outside a class with a
 *  supertype. Drives override completion. */
fun KotlinResolver.overridableMembersAt(offset: Int): List<KotlinSymbol> {
    val cls = enclosingClassOrObject(offset) ?: return emptyList()
    val superFqns = cls.superTypeListEntries.mapNotNull { e ->
        e.typeReference?.text?.let { service.resolveTypeName(it, fileContext) }
    }
    if (superFqns.isEmpty()) return emptyList()
    val declared = cls.declarations.mapNotNull { d ->
        when (d) {
            is KtNamedFunction -> d.name; is KtProperty -> d.name; else -> null
        }
    }.toHashSet()
    val seen = HashSet<String>()
    val out = ArrayList<KotlinSymbol>()
    for (fqn in superFqns) {
        service.membersOf(fqn, emptyList(), null).filterIsInstance<KotlinSymbol>().forEach { m ->
            if (isOverridable(m) && m.name !in declared && seen.add(
                    m.name + "#" + (m.signature ?: "")
                )
            ) out += m
        }
    }
    return out
}

internal fun KotlinResolver.isOverridable(m: KotlinSymbol): Boolean {
    if (m.kind != SymbolKind.METHOD && m.kind != SymbolKind.FIELD) return false
    if (m.isExtension) return false
    if (Modifier.STATIC in m.modifiers || Modifier.PRIVATE in m.modifiers || Modifier.FINAL in m.modifiers) return false
    return true
}

//
// All three share [resolvedSupertypeMembers] and follow the engine's conservative contract: each returns a
// "can't decide → do nothing" result whenever any supertype is unresolved, the class uses interface
// delegation (`: I by impl`, which supplies the members invisibly), or there are no supertypes — so a
// parse-only model never false-positives. Member matching is name-based (+ arity / param simple-type names
// for functions): too loose only in the safe direction (a real error goes unreported, never the reverse).

/** Flattened members of [cls]'s whole RESOLVED supertype closure ([KotlinSymbolService.membersOf] already
 *  returns own+inherited), or null when it can't be computed safely — no supertypes, a `by` delegation, or
 *  any supertype FQN that doesn't resolve. Callers treat null as "back off, emit nothing". */
internal fun KotlinResolver.resolvedSupertypeMembers(cls: KtClassOrObject): List<KotlinSymbol>? {
    val entries = cls.superTypeListEntries
    if (entries.isEmpty()) return null
    if (entries.any { it is KtDelegatedSuperTypeEntry }) return null // `: Foo by delegate` supplies members
    val fqns =
        entries.map { it.typeReference?.text?.let { t -> service.resolveTypeName(t, fileContext) } }
    if (fqns.any { it == null }) return null // an unresolved supertype → we can't see the whole picture
    return fqns.filterNotNull().distinct()
        .flatMap { service.membersOf(it, emptyList(), null).filterIsInstance<KotlinSymbol>() }
        .filter { !it.isExtension }
}

/** The inheritance problems found on one class — consumed by the diagnostics layer. [missing] is populated
 *  only for a concrete implementor (an abstract class may leave abstracts unimplemented). */
class InheritanceReport(
    val missing: List<KotlinSymbol>,
    val overridesNothing: List<KtCallableDeclaration>,
    val needsOverride: List<Pair<KtCallableDeclaration, KotlinSymbol>>,
) {
    val isEmpty: Boolean get() = missing.isEmpty() && overridesNothing.isEmpty() && needsOverride.isEmpty()

    companion object {
        val EMPTY = InheritanceReport(emptyList(), emptyList(), emptyList())
    }
}

/**
 * All three inheritance checks for [cls] in ONE pass (the supertype closure is resolved once, not per
 * member — important for per-keystroke cost). [concrete] gates the missing-abstract part (false for an
 * abstract/sealed class, which may leave abstracts unimplemented). Returns [InheritanceReport.EMPTY] when
 * the closure can't be resolved safely (see [resolvedSupertypeMembers]) — the conservative back-off.
 */
fun KotlinResolver.inheritanceProblems(cls: KtClassOrObject, concrete: Boolean): InheritanceReport {
    val closure = resolvedSupertypeMembers(cls) ?: return InheritanceReport.EMPTY
    val byName: Map<String, List<KotlinSymbol>> = closure.groupBy { it.name }
    val missing = if (concrete) unimplementedFrom(cls, closure) else emptyList()
    val overridesNothing = ArrayList<KtCallableDeclaration>()
    val needsOverride = ArrayList<Pair<KtCallableDeclaration, KotlinSymbol>>()
    for (d in cls.declarations) {
        val member = d as? KtCallableDeclaration ?: continue
        if (member !is KtNamedFunction && member !is KtProperty) continue
        val name = member.name ?: continue
        val sameName = byName[name].orEmpty()
        if (member.hasModifier(KtTokens.OVERRIDE_KEYWORD)) {
            if (sameName.isEmpty()) overridesNothing += member // `override` but nothing carries this name
        } else if (!member.hasModifier(KtTokens.PRIVATE_KEYWORD)) {
            hiddenSupertypeMember(member, sameName)?.let { needsOverride += member to it }
        }
    }
    return InheritanceReport(missing, overridesNothing, needsOverride)
}

/**
 * The inherited ABSTRACT members [cls] leaves unimplemented — each as the [KotlinSymbol] to override (the
 * "must implement abstract member" error + the implement-members fix consume these). Empty when nothing is
 * missing OR the closure can't be resolved safely. An abstract member is "provided" if a concrete member of
 * the same [memberKey] exists in [cls] or anywhere up the chain. Standalone entry for the quick-fix.
 */
fun KotlinResolver.unimplementedAbstractMembers(cls: KtClassOrObject): List<KotlinSymbol> =
    unimplementedFrom(cls, resolvedSupertypeMembers(cls) ?: return emptyList())

internal fun KotlinResolver.unimplementedFrom(
    cls: KtClassOrObject,
    closure: List<KotlinSymbol>
): List<KotlinSymbol> {
    val provided = HashSet(ownMemberKeys(cls))
    closure.forEach {
        if (it.isImplementableMember() && Modifier.ABSTRACT !in it.modifiers) provided += memberKey(
            it
        )
    }
    val required = LinkedHashMap<String, KotlinSymbol>()
    closure.forEach { m ->
        if (m.isImplementableMember() && Modifier.ABSTRACT in m.modifiers) memberKey(m).let {
            if (it !in provided) required.putIfAbsent(
                it,
                m
            )
        }
    }
    return required.values.toList()
}

/** Only a method or a property is an "abstract member to implement" — a nested type (a `@Metadata`/bytecode
 *  nested `interface`/`abstract class` surfaces as a `CLASS`-kind member carrying `ABSTRACT`, e.g.
 *  `Activity.ScreenCaptureCallback`), a constructor, or an enum constant must never be required. */
private fun KotlinSymbol.isImplementableMember(): Boolean =
    kind == SymbolKind.METHOD || kind == SymbolKind.FIELD

/** The inherited open/abstract member [member] (declared WITHOUT `override`) hides, or null. Matches a
 *  function on arity + param simple-type names (so a genuine overload `f(String)` vs inherited `f(Int)` does
 *  NOT match) and backs off on a `final`/`static` match (a different error). [sameName] = closure members
 *  sharing the name. */
internal fun KotlinResolver.hiddenSupertypeMember(
    member: KtCallableDeclaration,
    sameName: List<KotlinSymbol>
): KotlinSymbol? {
    if (sameName.isEmpty()) return null
    val isFun = member is KtNamedFunction
    val localParams =
        if (member is KtNamedFunction) member.valueParameters.map { simpleTypeName(it.typeReference?.text) } else emptyList()
    val matches = sameName.filter { m ->
        (m.kind == SymbolKind.METHOD) == isFun &&
                if (isFun) m.paramTypes.size == localParams.size &&
                        m.paramTypes.indices.all { i -> paramSimpleName(m.paramTypes[i]) == localParams[i] }
                else true
    }
    if (matches.isEmpty()) return null
    if (matches.any { Modifier.FINAL in it.modifiers || Modifier.STATIC in it.modifiers }) return null
    return matches.first()
}

/** A name+shape key so a concrete member of the same shape (in the class or up the chain) counts as
 *  implementing an abstract one. Functions key on name+arity (generics don't change arity); properties on name. */
internal fun KotlinResolver.memberKey(m: KotlinSymbol): String =
    if (m.kind == SymbolKind.METHOD) "M:${m.name}/${m.paramTypes.size}" else "P:${m.name}"

/** The keys [cls] itself supplies: declared functions/properties + primary-constructor `val`/`var`
 *  properties. The `override` keyword is irrelevant here (a missing one is the override-required check's
 *  concern, not a missing implementation). */
internal fun KotlinResolver.ownMemberKeys(cls: KtClassOrObject): Set<String> {
    val out = HashSet<String>()
    cls.declarations.forEach { d ->
        when (d) {
            is KtNamedFunction -> d.name?.let { out += "M:$it/${d.valueParameters.size}" }
            is KtProperty -> d.name?.let { out += "P:$it" }
            else -> {}
        }
    }
    cls.primaryConstructorParameters.forEach { p -> if (p.hasValOrVar()) p.name?.let { out += "P:$it" } }
    return out
}
