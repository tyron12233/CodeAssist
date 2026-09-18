package dev.ide.lang.kotlin.resolve

import dev.ide.kotlin.syntax.psi.KtCallableDeclaration
import dev.ide.kotlin.syntax.psi.KtClassOrObject
import dev.ide.kotlin.syntax.psi.KtDelegatedSuperTypeEntry
import dev.ide.kotlin.syntax.psi.KtNamedFunction
import dev.ide.kotlin.syntax.psi.KtProperty
import dev.ide.kotlin.syntax.psi.KtTokens
import dev.ide.kotlin.syntax.psi.containingClassOrObject
import dev.ide.lang.kotlin.symbols.KotlinSymbol
import dev.ide.lang.kotlin.symbols.KotlinSymbolService
import dev.ide.lang.resolve.Modifier
import dev.ide.lang.resolve.SymbolKind

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
    /** Each `override` function whose `suspend`-ness disagrees with the supertype member it overrides,
     *  paired with that member. Both directions are compile errors. */
    val suspendMismatch: List<Pair<KtCallableDeclaration, KotlinSymbol>> = emptyList(),
) {
    val isEmpty: Boolean
        get() = missing.isEmpty() && overridesNothing.isEmpty() && needsOverride.isEmpty() &&
                suspendMismatch.isEmpty()

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
    // `overridesNothing` fires on ABSENCE from the closure, so it is sound only when the closure is fully
    // enumerable. A binary/framework DIRECT supertype (`android.view.View`, `ComponentActivity`) reaches
    // inherited members through boot-classpath ancestors the symbol reader may not have read — its chain
    // enumeration is best-effort, so a valid `override fun onDraw` could look like it overrides nothing. Back
    // off in that case (mirroring the `super.member` guard); `missing`/`needsOverride` fire on POSITIVE finds
    // and so stay sound under an incomplete closure.
    val closureFullyEnumerable = cls.superTypeListEntries.all { e ->
        e.typeReference?.text?.let { service.resolveTypeName(it, fileContext) }?.let { service.sourceClass(it) != null } == true
    }
    val overridesNothing = ArrayList<KtCallableDeclaration>()
    val needsOverride = ArrayList<Pair<KtCallableDeclaration, KotlinSymbol>>()
    val suspendMismatch = ArrayList<Pair<KtCallableDeclaration, KotlinSymbol>>()
    for (d in cls.declarations) {
        val member = d as? KtCallableDeclaration ?: continue
        if (member !is KtNamedFunction && member !is KtProperty) continue
        val name = member.name ?: continue
        val sameName = byName[name].orEmpty()
        if (member.hasModifier(KtTokens.OVERRIDE_KEYWORD)) {
            if (closureFullyEnumerable && sameName.isEmpty()) overridesNothing += member // `override` but nothing carries this name
            else suspendMismatchedOverride(member, sameName)?.let { suspendMismatch += member to it }
        } else if (!member.hasModifier(KtTokens.PRIVATE_KEYWORD)) {
            hiddenSupertypeMember(member, sameName)?.let { needsOverride += member to it }
        }
    }
    return InheritanceReport(missing, overridesNothing, needsOverride, suspendMismatch)
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
            if (it !in provided) required.getOrPut(it) { m }
        }
    }
    val result = required.values.toList()
    // A @Parcelize class has its Parcelable implementation (writeToParcel()/describeContents() + CREATOR)
    // generated by the kotlin-parcelize compiler plugin at build time. The parse-only editor can't see those
    // synthetic members, so it must not flag them as unimplemented (matching the Parcelize IDE support in
    // Android Studio). Only the two Parcelable methods are dropped — any OTHER genuinely-missing abstract
    // member is still reported. The compile itself supplies them once the plugin runs.
    return if (cls.isParcelizeAnnotated()) result.filterNot { it.name in PARCELIZE_GENERATED_MEMBERS } else result
}

/** The Parcelable abstract methods the kotlin-parcelize compiler plugin generates for a `@Parcelize` class. */
private val PARCELIZE_GENERATED_MEMBERS = setOf("writeToParcel", "describeContents")

/** True if [this] carries `@Parcelize` (matched by short name, so both `kotlinx.parcelize.Parcelize` and the
 *  legacy `kotlinx.android.parcel.Parcelize` count — the parse-only model can't always resolve the FQN). */
private fun KtClassOrObject.isParcelizeAnnotated(): Boolean =
    annotationEntries.any { it.shortName?.asString() == "Parcelize" }

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
    val matches = sameShapeMembers(member, sameName)
    if (matches.isEmpty()) return null
    if (matches.any { Modifier.FINAL in it.modifiers || Modifier.STATIC in it.modifiers }) return null
    return matches.first()
}

/** The [sameName] members [member] could be overriding: same kind, and for a function the same arity + param
 *  simple-type names. Loose only in the safe direction — a substituted generic parameter spells differently
 *  and so drops out, leaving the caller with nothing to report. */
private fun KotlinResolver.sameShapeMembers(
    member: KtCallableDeclaration,
    sameName: List<KotlinSymbol>
): List<KotlinSymbol> {
    val isFun = member is KtNamedFunction
    val localParams =
        if (member is KtNamedFunction) member.valueParameters.map { simpleTypeName(it.typeReference?.text) } else emptyList()
    return sameName.filter { m ->
        (m.kind == SymbolKind.METHOD) == isFun &&
                if (isFun) m.paramTypes.size == localParams.size &&
                        m.paramTypes.indices.all { i -> paramSimpleName(m.paramTypes[i]) == localParams[i] }
                else true
    }
}

/**
 * The supertype function whose `suspend`-ness the `override` [member] contradicts, or null. Kotlin errors both
 * ways — "Non-suspend function 'load' cannot override suspend function" (what the generated "Implement members"
 * stub used to produce, and what nothing flagged until the user built) and "Suspend function 'load' cannot
 * override non-suspend function".
 *
 * Fires only on POSITIVE agreement-free evidence, the same conservative contract as the rest of this file: it
 * needs a supertype candidate of the SAME shape, and stays silent the moment ANY candidate agrees with the
 * member. So an unknown/unmatched supertype reports nothing, and a supertype member whose `suspend` flag never
 * reached the model can only silence the check, never invent an error. (A `suspend` member decoded from plain
 * bytecode — no `@Metadata` — carries its `Continuation` parameter, so it differs in ARITY and drops out of
 * the candidates rather than reading as non-suspend.)
 */
internal fun KotlinResolver.suspendMismatchedOverride(
    member: KtCallableDeclaration,
    sameName: List<KotlinSymbol>
): KotlinSymbol? {
    val fn = member as? KtNamedFunction ?: return null // only a function can be `suspend`
    if (sameName.isEmpty()) return null
    val candidates = sameShapeMembers(fn, sameName)
    if (candidates.isEmpty()) return null // can't tell which member it overrides
    val isSuspend = fn.hasModifier(KtTokens.SUSPEND_KEYWORD)
    if (candidates.any { it.isSuspend == isSuspend }) return null // it agrees with one of them
    return candidates.first()
}

/** The supertype member whose `suspend`-ness the `override` [member] contradicts, or null — the standalone
 *  entry for the quick-fix (the diagnostic pass computes the same thing for the whole class at once). Null
 *  for a member that isn't an `override`, isn't inside a class, or whose supertype closure can't be resolved,
 *  so a stale diagnostic yields no fix. */
fun KotlinResolver.suspendMismatchFor(member: KtCallableDeclaration): KotlinSymbol? {
    if (!member.hasModifier(KtTokens.OVERRIDE_KEYWORD)) return null
    val name = member.name ?: return null
    val cls = member.containingClassOrObject ?: return null
    val closure = resolvedSupertypeMembers(cls) ?: return null
    return suspendMismatchedOverride(member, closure.filter { it.name == name })
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
