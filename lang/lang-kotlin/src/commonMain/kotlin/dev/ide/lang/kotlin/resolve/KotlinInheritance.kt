package dev.ide.lang.kotlin.resolve

import dev.ide.kotlin.syntax.psi.KtCallableDeclaration
import dev.ide.kotlin.syntax.psi.KtClassOrObject
import dev.ide.kotlin.syntax.psi.KtDelegatedSuperTypeEntry
import dev.ide.kotlin.syntax.psi.KtNamedFunction
import dev.ide.kotlin.syntax.psi.KtProperty
import dev.ide.kotlin.syntax.psi.KtTokens
import dev.ide.kotlin.syntax.psi.containingClassOrObject
import dev.ide.kotlin.syntax.psi.getStrictParentOfType
import dev.ide.lang.kotlin.symbols.KotlinSymbol
import dev.ide.lang.kotlin.symbols.KotlinType
import dev.ide.lang.kotlin.symbols.KotlinSymbolService
import dev.ide.lang.resolve.Modifier
import dev.ide.lang.resolve.SymbolKind

/** Inheritance analysis: supertype member closure, overridable members, and unimplemented-abstract / hidden-member reports. */

/** The inherited members overridable at [offset]: every non-final/non-private/non-static member of the
 *  enclosing class's declared supertypes, minus those it already declares. Empty outside a class with a
 *  supertype. Drives override completion. */
fun KotlinResolver.overridableMembersAt(offset: Int): List<KotlinSymbol> {
    val cls = enclosingClassOrObject(offset) ?: return emptyList()
    // Each supertype with the arguments it is given (`List<String>`), so an inherited member reads as the class
    // sees it (`get(index: Int): String`, not `E`).
    val typeArgs = HashMap<String, List<dev.ide.lang.resolve.TypeRef>>()
    val declaredSupers = cls.superTypeListEntries.mapNotNull { e ->
        val text = e.typeReference?.text ?: return@mapNotNull null
        val fqn = service.resolveTypeName(text, fileContext) ?: return@mapNotNull null
        if ('<' in text) runCatching { service.typeFromText(text, fileContext) }.getOrNull()
            ?.takeIf { it.qualifiedName == fqn && it.typeArguments.isNotEmpty() }?.let { typeArgs[fqn] = it.typeArguments }
        fqn
    }
    // Every class also extends `Any`, named or not: `toString`, `equals` and `hashCode` are the three members
    // people override most, and a class with no supertype list was offered none of them.
    val isInterface = cls is dev.ide.kotlin.syntax.psi.KtClass && cls.isInterface()
    val superFqns = if (isInterface || "kotlin.Any" in declaredSupers) declaredSupers else declaredSupers + "kotlin.Any"
    if (superFqns.isEmpty()) return emptyList()
    val declared = cls.declarations.mapNotNull { d ->
        when (d) {
            is KtNamedFunction -> d.name; is KtProperty -> d.name; else -> null
        }
    }.toHashSet()
    // A property declared in the primary constructor is declared too (`class A(override val bar: Int)`).
    cls.primaryConstructorParameters.forEach { p -> if (p.hasValOrVar()) p.name?.let(declared::add) }
    val seen = HashSet<String>()
    val out = ArrayList<KotlinSymbol>()
    for (fqn in superFqns) {
        service.membersOf(fqn, typeArgs[fqn].orEmpty(), null).filterIsInstance<KotlinSymbol>().forEach { m ->
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
    // `Super<T1>.Inner` (an inner class's supertype) names `Super.Inner`: every segment's arguments go.
    val fqns =
        entries.map { it.typeReference?.text?.let { t -> service.resolveTypeName(service.withoutTypeArguments(t), fileContext) } }
    if (fqns.any { it == null }) return null // an unresolved supertype → we can't see the whole picture
    // A supertype whose own shape is not readable (`java.util.ArrayList` before the JDK or android.jar is
    // indexed) still brings its mapped Kotlin interfaces' abstract members into the closure, but none of its
    // implementations: every `List` member then read as unimplemented.
    if (fqns.any { !service.isKnownType(it!!) }) return null
    // Own + inherited members only: a supertype's MEMBER extensions (`RowScope`'s `fun Modifier.weight()`) are
    // members to override like any other, while the receiver's top-level extensions are not members at all.
    return fqns.filterNotNull().distinct().flatMap { service.ownAndInheritedMembers(it) }
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
    /** Each `override` of a member that is FINAL in the supertype, paired with that member. */
    val overridesFinal: List<Pair<KtCallableDeclaration, KotlinSymbol>> = emptyList(),
    /** Each `override` with a declared type, paired with every same-shape supertype member it could be
     *  overriding. The diagnostics layer reports the ones whose type no candidate accepts. */
    val overrideCandidates: List<Pair<KtCallableDeclaration, List<KotlinSymbol>>> = emptyList(),
    /** Each `override` whose written visibility is narrower than (or not comparable with) a project member it
     *  overrides, with that member and the member's visibility (`public`, `protected`, `internal`). */
    val weakenedAccess: List<Triple<KtCallableDeclaration, KotlinSymbol, String>> = emptyList(),
) {
    val isEmpty: Boolean
        get() = missing.isEmpty() && overridesNothing.isEmpty() && needsOverride.isEmpty() &&
                suspendMismatch.isEmpty() && overridesFinal.isEmpty() && overrideCandidates.isEmpty() &&
                weakenedAccess.isEmpty()

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
    val overridesFinal = ArrayList<Pair<KtCallableDeclaration, KotlinSymbol>>()
    val overrideCandidates = ArrayList<Pair<KtCallableDeclaration, List<KotlinSymbol>>>()
    val weakenedAccess = ArrayList<Triple<KtCallableDeclaration, KotlinSymbol, String>>()
    for (d in cls.declarations) {
        val member = d as? KtCallableDeclaration ?: continue
        if (member !is KtNamedFunction && member !is KtProperty) continue
        val name = member.name ?: continue
        val sameName = byName[name].orEmpty()
        if (member.hasModifier(KtTokens.OVERRIDE_KEYWORD)) {
            if (closureFullyEnumerable && sameName.isEmpty()) overridesNothing += member // `override` but nothing carries this name
            // Something carries the name, but no declaration of it can be what this overrides: a different
            // parameter list (count, vararg, a type that provably differs, an extension receiver or not), or a
            // property where only functions carry the name (and the reverse).
            else if (closureFullyEnumerable && noSupertypeMemberFits(member, sameName, cls)) overridesNothing += member
            else suspendMismatchedOverride(member, sameName)?.let { suspendMismatch += member to it }
            val shaped = sameShapeMembers(member, sameName)
            // Final in EVERY supertype that carries it: one open declaration is enough to make it overridable.
            if (shaped.isNotEmpty() && shaped.all { isFinalMember(it) }) overridesFinal += member to shaped.first()
            else if (shaped.isNotEmpty() && member.typeReference != null) overrideCandidates += member to shaped
            narrowedVisibility(member, shaped)?.let { weakenedAccess += Triple(member, it.first, it.second) }
            narrowedSetter(member, shaped)?.let { weakenedAccess += Triple(member, it, "public setter") }
        } else if (!member.hasModifier(KtTokens.PRIVATE_KEYWORD)) {
            hiddenSupertypeMember(member, sameName)?.let { needsOverride += member to it }
        }
    }
    return InheritanceReport(missing, overridesNothing, needsOverride, suspendMismatch, overridesFinal, overrideCandidates, weakenedAccess)
}

/**
 * The overridden project property whose public setter the `override var` [member] narrows with a `private`,
 * `protected` or `internal` setter of its own. Only a source property declared `var` with no visibility on it
 * or its setter counts, and only one that is not itself an `override`.
 */
private fun narrowedSetter(member: KtCallableDeclaration, shaped: List<KotlinSymbol>): KotlinSymbol? {
    if (member !is dev.ide.kotlin.syntax.psi.KtProperty || !member.isVar) return null
    val setter = member.setter ?: return null
    if (!setter.hasModifier(KtTokens.PRIVATE_KEYWORD) && !setter.hasModifier(KtTokens.PROTECTED_KEYWORD) &&
        !setter.hasModifier(KtTokens.INTERNAL_KEYWORD)) return null
    for (m in shaped) {
        if (!m.origin.fromSource) continue
        val psi = (m.declaration() as? dev.ide.lang.kotlin.parse.KotlinDomNode)?.psi
        val isVar = when (psi) {
            is dev.ide.kotlin.syntax.psi.KtProperty -> psi.isVar
            is dev.ide.kotlin.syntax.psi.KtParameter -> psi.isMutable
            else -> continue
        }
        if (!isVar) continue
        psi as dev.ide.kotlin.syntax.psi.KtModifierListOwner
        if (psi.hasModifier(KtTokens.OVERRIDE_KEYWORD) || psi.hasModifier(KtTokens.PRIVATE_KEYWORD) ||
            psi.hasModifier(KtTokens.PROTECTED_KEYWORD) || psi.hasModifier(KtTokens.INTERNAL_KEYWORD)) continue
        val theirSetter = (psi as? dev.ide.kotlin.syntax.psi.KtProperty)?.setter
        if (theirSetter != null && (theirSetter.hasModifier(KtTokens.PRIVATE_KEYWORD) || theirSetter.hasModifier(KtTokens.PROTECTED_KEYWORD) ||
                theirSetter.hasModifier(KtTokens.INTERNAL_KEYWORD))) continue
        return m
    }
    return null
}

/**
 * The overridden project member whose visibility the `override` [member] narrows or changes, with that
 * visibility: `protected override` of a public member, `internal override` of a public or protected one. Only
 * a visibility written on the override counts (without one it inherits the overridden member's), and only a
 * source member's visibility is trusted: one that is itself an `override` with none written inherits one this
 * does not chase.
 */
private fun narrowedVisibility(member: KtCallableDeclaration, shaped: List<KotlinSymbol>): Pair<KotlinSymbol, String>? {
    val own = when {
        member.hasModifier(KtTokens.PROTECTED_KEYWORD) -> "protected"
        member.hasModifier(KtTokens.INTERNAL_KEYWORD) -> "internal"
        else -> return null
    }
    for (m in shaped) {
        if (!m.origin.fromSource) continue
        val psi = (m.declaration() as? dev.ide.lang.kotlin.parse.KotlinDomNode)?.psi as? dev.ide.kotlin.syntax.psi.KtModifierListOwner ?: continue
        val theirs = when {
            psi.hasModifier(KtTokens.PUBLIC_KEYWORD) -> "public"
            psi.hasModifier(KtTokens.PROTECTED_KEYWORD) -> "protected"
            psi.hasModifier(KtTokens.INTERNAL_KEYWORD) -> "internal"
            psi.hasModifier(KtTokens.PRIVATE_KEYWORD) || psi.hasModifier(KtTokens.OVERRIDE_KEYWORD) -> continue
            else -> "public"
        }
        if (theirs != own) return m to theirs
    }
    return null
}

/**
 * Whether [m] is known to be final: a Java member carrying `final`, or a member of a project class whose
 * declaration is neither `open`, `abstract` nor itself an `override`, outside an interface. A member read from
 * Kotlin metadata does not say, and counts as overridable.
 */
internal fun KotlinResolver.isFinalMember(m: KotlinSymbol): Boolean {
    if (Modifier.FINAL in m.modifiers) return true
    if (!m.origin.fromSource) return false
    val psi = (m.declaration() as? dev.ide.lang.kotlin.parse.KotlinDomNode)?.psi as? dev.ide.kotlin.syntax.psi.KtModifierListOwner
        ?: return false
    if (psi !is KtNamedFunction && psi !is KtProperty && psi !is dev.ide.kotlin.syntax.psi.KtParameter) return false
    if (psi.hasModifier(KtTokens.FINAL_KEYWORD)) return true
    if (psi.hasModifier(KtTokens.OPEN_KEYWORD) || psi.hasModifier(KtTokens.ABSTRACT_KEYWORD) || psi.hasModifier(KtTokens.OVERRIDE_KEYWORD)) return false
    val owner = (psi as dev.ide.kotlin.syntax.psi.KtElement).getStrictParentOfType<KtClassOrObject>() ?: return false
    return !(owner is dev.ide.kotlin.syntax.psi.KtClass && owner.isInterface())
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
        if (it.isImplementableMember() && Modifier.ABSTRACT !in it.modifiers) {
            provided += memberKey(it)
            jvmMappedKey(it)?.let { k -> provided += k }
        }
    }
    val required = LinkedHashMap<String, KotlinSymbol>()
    closure.forEach { m ->
        if (m.isImplementableMember() && Modifier.ABSTRACT in m.modifiers) memberKey(m).let {
            if (it !in provided) required.getOrPut(it) { m }
        }
    }
    // A project superclass that implements an interface `by` a delegate (`abstract class Base : I by impl`)
    // implements every member of that interface, so `class C : Base()` inherits them implemented.
    if (required.isNotEmpty()) delegatedSupertypeMembers(cls).forEach { required.remove(memberKey(it)) }
    val result = required.values.toList()
    // A @Parcelize class has its Parcelable implementation (writeToParcel()/describeContents() + CREATOR)
    // generated by the kotlin-parcelize compiler plugin at build time. The parse-only editor can't see those
    // synthetic members, so it must not flag them as unimplemented (matching the Parcelize IDE support in
    // Android Studio). Only the two Parcelable methods are dropped — any OTHER genuinely-missing abstract
    // member is still reported. The compile itself supplies them once the plugin runs.
    return if (cls.isParcelizeAnnotated()) result.filterNot { it.name in PARCELIZE_GENERATED_MEMBERS } else result
}

/** The members a project-source class in [cls]'s supertype closure implements by delegation: every member of
 *  each interface it delegates (`: I by d`), with that interface's own supertypes. */
private fun KotlinResolver.delegatedSupertypeMembers(cls: KtClassOrObject): List<KotlinSymbol> {
    val out = ArrayList<KotlinSymbol>()
    val seen = HashSet<String>()
    val queue = ArrayDeque<String>()
    cls.superTypeListEntries.forEach { e -> e.typeReference?.text?.let { service.resolveTypeName(it, fileContext) }?.let { queue += it } }
    while (queue.isNotEmpty()) {
        val fqn = queue.removeFirst()
        if (!seen.add(fqn)) continue
        val rc = service.sourceClass(fqn) ?: continue
        val psi = (rc.node as? dev.ide.lang.kotlin.parse.KotlinDomNode)?.psi as? KtClassOrObject ?: continue
        val ctx = rc.ctx
        for (e in psi.superTypeListEntries) {
            if (e !is KtDelegatedSuperTypeEntry) continue
            val t = e.typeReference?.text?.let { service.resolveTypeName(it, ctx) } ?: continue
            out += service.ownAndInheritedMembers(t)
        }
        service.supertypesOf(fqn).forEach { queue += it.qualifiedName }
    }
    return out
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
    // K2's FirOverrideChecker: only a member the subclass can SEE is overridden (a superclass's `private fun foo()`
    // is not), and only one whose signature agrees once the supertype's arguments are substituted
    // (`foo(List<Int>)` does not override `foo(List<String>)`).
    val matches = sameShapeMembers(member, sameName).filter { Modifier.PRIVATE !in it.modifiers && sameParameterArguments(member, it) }
    if (matches.isEmpty()) return null
    if (matches.any { Modifier.FINAL in it.modifiers || Modifier.STATIC in it.modifiers }) return null
    // FirJvmOverridesBackwardCompatibilityHelper: overriding a Java member of a class mapped to a Kotlin built-in
    // (`Throwable.fillInStackTrace()`) needs no `override`.
    if (matches.all { it.declaringClassFqn in JVM_MAPPED_JAVA_CLASSES }) return null
    return matches.first()
}

/** The Java classes K2's JavaToKotlinClassMap maps to a Kotlin built-in. */
private val JVM_MAPPED_JAVA_CLASSES = setOf(
    "java.lang.Object", "java.lang.String", "java.lang.CharSequence", "java.lang.Throwable", "java.lang.Cloneable",
    "java.lang.Number", "java.lang.Comparable", "java.lang.Enum", "java.lang.annotation.Annotation",
    "java.lang.Iterable", "java.util.Iterator", "java.util.Collection", "java.util.List", "java.util.Set",
    "java.util.ListIterator", "java.util.Map", "java.util.Map.Entry", "java.util.Map\$Entry",
)

/**
 * Whether [member]'s parameters and the supertype function [sup]'s agree in their type arguments, with [sup]'s
 * class's type parameters bound to the arguments [member]'s class writes for that supertype. A pair of arguments
 * disagrees only when both are known, non-parameter types of different classes.
 */
private fun KotlinResolver.sameParameterArguments(member: KtCallableDeclaration, sup: KotlinSymbol): Boolean {
    val fn = member as? KtNamedFunction ?: return true
    val cls = fn.getStrictParentOfType<KtClassOrObject>() ?: return true
    val owner = cls.fqName?.asString()
    val declaring = sup.declaringClassFqn
        ?: ((sup.declaration() as? dev.ide.lang.kotlin.parse.KotlinDomNode)?.psi as? KtCallableDeclaration)
            ?.getStrictParentOfType<KtClassOrObject>()?.fqName?.asString()
    val bindings = if (declaring == null) emptyMap() else supertypeBindings(cls, declaring)
    for ((i, p) in fn.valueParameters.withIndex()) {
        val local = service.typeFromText(p.typeReference?.text ?: continue, fileContext, owner) ?: continue
        val theirs = sup.paramTypes.getOrNull(i)?.let { service.substitute(it, bindings) } as? KotlinType ?: continue
        if (canonicalTypeName(local.qualifiedName) != canonicalTypeName(theirs.qualifiedName)) continue
        if (local.typeArguments.size != theirs.typeArguments.size) continue
        for ((a, b) in local.typeArguments.zip(theirs.typeArguments)) {
            val x = a as? KotlinType ?: continue
            val y = b as? KotlinType ?: continue
            if (x.isTypeParameter || y.isTypeParameter || x.projection == "*" || y.projection == "*") continue
            if (!service.isKnownType(x.qualifiedName) || !service.isKnownType(y.qualifiedName)) continue
            if (canonicalTypeName(x.qualifiedName) != canonicalTypeName(y.qualifiedName)) return false
        }
    }
    return true
}

private fun canonicalTypeName(fqn: String): String = when (fqn) {
    "java.lang.String" -> "kotlin.String"
    "java.lang.Integer", "int" -> "kotlin.Int"
    "java.lang.Long", "long" -> "kotlin.Long"
    "java.lang.Object" -> "kotlin.Any"
    "java.util.List" -> "kotlin.collections.List"
    else -> fqn
}

/** The bindings of [declFqn]'s type parameters to the arguments [cls] writes for it as a DIRECT supertype; empty
 *  when [declFqn] is not one (deeper supertypes keep their parameters, which then never disagree). */
private fun KotlinResolver.supertypeBindings(cls: KtClassOrObject, declFqn: String): Map<String, dev.ide.lang.resolve.TypeRef> {
    val names = service.classTypeParameters(declFqn)
    if (names.isEmpty()) return emptyMap()
    val owner = cls.fqName?.asString()
    for (e in cls.superTypeListEntries) {
        val text = e.typeReference?.text ?: continue
        val t = service.typeFromText(text, fileContext, owner) ?: continue
        if (t.qualifiedName != declFqn || t.typeArguments.size != names.size) continue
        return names.zip(t.typeArguments).toMap()
    }
    return emptyMap()
}

/** The [sameName] members [member] could be overriding: same kind, and for a function the same arity + param
 *  simple-type names. Loose only in the safe direction — a substituted generic parameter spells differently
 *  and so drops out, leaving the caller with nothing to report. */
/** Whether none of [sameName] can be the member [member] overrides; any candidate that might be answers false. */
private fun KotlinResolver.noSupertypeMemberFits(member: KtCallableDeclaration, sameName: List<KotlinSymbol>, cls: KtClassOrObject): Boolean {
    if (member is KtProperty) return sameName.all { it.kind == SymbolKind.METHOD }
    val fn = member as? KtNamedFunction ?: return false
    if (fn.typeParameters.isNotEmpty()) return false
    val functions = sameName.filter { it.kind == SymbolKind.METHOD }
    if (functions.isEmpty()) return true
    val params = fn.valueParameters
    val varargAt = params.indexOfFirst { it.isVarArg }
    val owner = cls.fqName?.asString()
    fun typesMayMatch(written: dev.ide.kotlin.syntax.psi.KtTypeReference?, candidate: KotlinType): Boolean {
        if (candidate.isTypeParameter || !service.isKnownType(candidate.qualifiedName)) return true
        val mine = written?.text?.let { service.typeFromText(it, fileContext, owner) } ?: return true
        if (mine.isTypeParameter || !service.isKnownType(mine.qualifiedName)) return true
        val a = dev.ide.lang.kotlin.symbols.Builtins.kotlinTypeFor(mine.qualifiedName) ?: mine.qualifiedName
        val b = dev.ide.lang.kotlin.symbols.Builtins.kotlinTypeFor(candidate.qualifiedName) ?: candidate.qualifiedName
        if (a != b) return false
        if (mine.typeArguments.size != candidate.typeArguments.size) return false
        return true
    }
    return functions.none { f ->
        if (f.isExtension != (fn.receiverTypeReference != null)) return@none false
        if (f.paramTypes.size != params.size) return@none false
        if (f.varargParamIndex != varargAt) return@none false
        if (f.typeParameters.isNotEmpty()) return@none true
        params.indices.all { i ->
            val cp = f.paramTypes[i] as? KotlinType ?: return@all true
            typesMayMatch(params[i].typeReference, cp)
        }
    }
}

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

/**
 * The Kotlin member a concrete JAVA method implements under the built-in mapping: `java.util.ArrayList`'s
 * `size()` IS `kotlin.collections.List.size`, its `remove(int)` IS `MutableList.removeAt`, `charAt` is
 * `CharSequence.get`, `intValue()` is `Number.toInt()`. Without it `class L : java.util.ArrayList<Int>()` was
 * told to implement `size` and `removeAt`. Null for a method the mapping does not rename.
 */
private fun jvmMappedKey(m: KotlinSymbol): String? {
    if (m.kind != SymbolKind.METHOD) return null
    val arity = m.paramTypes.size
    if (arity == 0) JVM_MAPPED_PROPERTIES[m.name]?.let { return "P:$it" }
    if (arity == 0) JVM_MAPPED_NUMBER_METHODS[m.name]?.let { return "M:$it/0" }
    if (arity == 1 && m.name == "charAt") return "M:get/1"
    if (arity == 1 && m.name == "remove") {
        val p = m.paramTypes[0]?.qualifiedName
        if (p == "int" || p == "kotlin.Int") return "M:removeAt/1"
    }
    return null
}

private val JVM_MAPPED_PROPERTIES = mapOf(
    "size" to "size", "length" to "length", "entrySet" to "entries", "keySet" to "keys", "values" to "values",
)
private val JVM_MAPPED_NUMBER_METHODS = mapOf(
    "intValue" to "toInt", "longValue" to "toLong", "doubleValue" to "toDouble", "floatValue" to "toFloat",
    "shortValue" to "toShort", "byteValue" to "toByte", "charValue" to "toChar",
)

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
    // A data class's generated members implement abstract ones of the same shape (`interface T { fun
    // component1(): Int }` is satisfied by `data class A(val x: Int) : T`).
    if (cls is dev.ide.kotlin.syntax.psi.KtClass && cls.isData()) {
        val n = cls.primaryConstructorParameters.count { it.hasValOrVar() }
        for (i in 1..n) out += "M:component$i/0"
        out += "M:copy/$n"; out += "M:toString/0"; out += "M:hashCode/0"; out += "M:equals/1"
    }
    // The JVM spelling of a mapped member the class implements the Kotlin way: an `override val size` is also
    // the `size()` a Java `AbstractList` supertype declares abstract.
    for (k in out.toList()) {
        if (k.startsWith("P:")) KOTLIN_TO_JVM_PROPERTY[k.removePrefix("P:")]?.let { out += "M:$it/0" }
        if (k == "M:removeAt/1") out += "M:remove/1"
        if (k == "M:get/1") out += "M:charAt/1"
    }
    return out
}

private val KOTLIN_TO_JVM_PROPERTY = mapOf(
    "size" to "size", "length" to "length", "entries" to "entrySet", "keys" to "keySet", "values" to "values",
)
