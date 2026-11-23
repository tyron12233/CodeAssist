package dev.ide.lang.kotlin.resolve

import dev.ide.lang.kotlin.symbols.Builtins
import dev.ide.lang.kotlin.symbols.KotlinSymbolService
import dev.ide.lang.kotlin.symbols.KotlinType
import dev.ide.lang.kotlin.symbols.TypeRendering
import dev.ide.lang.resolve.TypeRef

/**
 * A bounded constraint system over a set of type variables — the editor resolver's stand-in for the K2
 * compiler's `NewConstraintSystem`. Each variable accumulates LOWER / UPPER / EXACT bounds; a subtyping
 * constraint is DECOMPOSED (a generic constraint recurses over type arguments, a cross-classifier one is
 * projected through the supertype it instantiates) down to bounds on variables or to a concrete-vs-concrete
 * subtyping CHECK. A lower bound that is not a subtype of an upper bound — or a concrete `A <: B` where `B`
 * is not a supertype of `A` — is a CONTRADICTION, which is what overload resolution reads to mark a candidate
 * INAPPLICABLE and discard it (the compiler's applicability step). [solve] fixes each variable to its EXACT
 * bound, else the common-supertype of its lower bounds, else its upper bound, else a supplied fallback.
 *
 * TERMINATION: decomposition is mutually recursive with the per-variable [check], and a variable's bound can
 * mention the variable itself (an F-bounded parameter, `<T : Comparable<T>>`), so a decomposed bound can land
 * back on the variable that produced it. Three things keep that finite: a bound already recorded is not added
 * again, so the same constraint cannot re-trigger its own check ([addBound]); [check] is re-entrancy-safe and
 * picks up bounds added underneath it by INDEX rather than recursing once per bound; and [MAX_DEPTH] /
 * [MAX_BOUNDS] cap nesting and bound count for an expansive hierarchy whose projection widens every round.
 * Abandoning a constraint can only MISS a contradiction, never invent one.
 *
 * FIDELITY: the [KotlinType] model carries no use-site variance projections, so generic subtyping is
 * decomposed COVARIANTLY (`Foo<A> <: Foo<B>` ⇒ `A <: B`). That is exact for the read-only collection types
 * inference rides (List/Set/Map/Iterable are declaration-site `out`) and a safe over-approximation elsewhere:
 * it can only MISS a contradiction (degrading to the caller's tiebreak), never invent one, so it never rejects
 * a call the compiler accepts. Faithful invariant/contravariant checking would need projections the model
 * doesn't retain.
 */
internal class KotlinConstraintSystem(
    private val service: KotlinSymbolService,
    /** Whether a variable's own `?` is read: a nullable variable below a non-null type (`T? <: Int`, which null
     *  itself breaks) is a contradiction, and `Int? <: T?` asks only `Int <: T`. Off for overload resolution, whose
     *  expected-type constraints this model reads loosely; on where a single candidate's own arguments are judged. */
    private val nullableVariableBelowNonNull: Boolean = false,
) {

    private class Var(val name: String) {
        val lower = ArrayList<KotlinType>()
        val upper = ArrayList<KotlinType>()
        var exact: KotlinType? = null

        /** The variable's DECLARED upper bound, as registered; the other upper bounds come from the call. */
        var declared: KotlinType? = null

        /** True while [check] is walking this variable's bounds. A decomposition under that walk can add
         *  ANOTHER bound to the same variable; the walk picks it up by index, so the nested call returns
         *  instead of starting a second (recursive) walk. */
        var checking = false
    }

    private val vars = LinkedHashMap<String, Var>()

    /** Current decomposition nesting, capped at [MAX_DEPTH]. */
    private var depth = 0

    /** True once an unsatisfiable constraint was seen — the candidate is INAPPLICABLE. */
    var hasContradiction = false
        private set

    /** Why the contradiction arose, where it says what K2 then does with the variable: a floor above the variable's
     *  DECLARED bound (`T : CharSequence` given an `Int`), or a constraint impossible at a nullable variable itself
     *  (`T? <: Int`). Either leaves the variable with no type it may take, which K2 reports as uninferred; two
     *  bounds from different arguments disagreeing does not (the variable is fixed from one, the other argument is
     *  the mismatch). */
    var variableLeftWithoutType = false
        private set

    /** The variables [variableLeftWithoutType] is about. */
    val variablesLeftWithoutType = LinkedHashSet<String>()

    /** Record a contradiction found outside the constraints themselves (a lambda of the wrong arity). */
    fun contradict() {
        hasContradiction = true
    }

    val isEmpty: Boolean get() = vars.isEmpty()

    /** Whether the variable [name] has any constraint besides its declared bound: a floor, an exact type, or a
     *  ceiling the constraints added. One with none has nothing to be inferred from. */
    fun isConstrained(name: String): Boolean {
        val v = vars[name] ?: return false
        return v.exact != null || v.lower.isNotEmpty() || v.upper.any { it !== v.declared }
    }

    fun registerVariable(name: String, upperBound: KotlinType? = null) {
        val v = vars.getOrPut(name) { Var(name) }
        if (upperBound != null && !upperBound.isTypeParameter && upperBound.qualifiedName != "kotlin.Any") {
            v.declared = upperBound
            if (addBound(v.upper, upperBound)) check(v)
        }
    }

    private fun variable(t: KotlinType?): Var? =
        if (t != null && t.isTypeParameter) vars[t.qualifiedName] else null

    /** Pin a variable exactly (an explicit type argument). A pin is both a floor and a ceiling, so it joins
     *  both bound lists and [check] walks it like any other bound. */
    fun fix(name: String, type: KotlinType) {
        vars[name]?.let {
            it.exact = type
            addBound(it.lower, type); addBound(it.upper, type)
            check(it)
        }
    }

    /** Constrain `sub <: sup`, decomposing until it reaches a variable bound or a concrete check. */
    fun addSubtypeConstraint(sub: TypeRef?, sup: TypeRef?) {
        val a = sub as? KotlinType ?: return
        val b = sup as? KotlinType ?: return
        if (depth >= MAX_DEPTH) return // an expansive hierarchy: stop decomposing rather than recurse forever
        depth++
        try {
            val bv = variable(b)
            val av = variable(a)
            when {
                bv != null && av != null -> {} // variable <: variable: skip (rare; both stay open, fixation handles)
                bv != null -> {
                    // `X <: T?` asks only `X!! <: T`: the `?` already takes null.
                    val floor = if (nullableVariableBelowNonNull && b.nullable && a.nullable) a.withNullable(false) else a
                    if (addBound(bv.lower, floor)) check(bv)
                }          // b ≥ a
                av != null -> {
                    // `T? <: B` holds for null only when B takes null.
                    if (nullableVariableBelowNonNull && a.nullable && !b.nullable && !b.isTypeParameter) {
                        hasContradiction = true; variableLeftWithoutType = true; variablesLeftWithoutType += av.name; return
                    }
                    if (addBound(av.upper, b)) check(av)
                }          // a ≤ b
                else -> checkConcrete(
                    a,
                    b
                ) // both concrete → decompose + check
            }
        } finally {
            depth--
        }
    }

    /** `A <: B` for concrete (non-variable) [a]/[b]: decompose over the classifier hierarchy, recording a
     *  CONTRADICTION when [b] is provably not a supertype of [a]. */
    private fun checkConcrete(a: KotlinType, b: KotlinType) {
        if (b.qualifiedName == "kotlin.Any" || b.qualifiedName == "java.lang.Object") return
        if (a.qualifiedName == "kotlin.Nothing") return // Nothing <: everything
        // A non-null upper cannot accept a nullable value (`String? <: String` is false).
        if (a.nullable && !b.nullable) {
            hasContradiction = true; return
        }
        if (a.qualifiedName == b.qualifiedName) {
            decompose(b.qualifiedName, a.typeArguments, b.typeArguments); return
        }
        // A Java parameter's collection type is flexible in mutability (`(Mutable)Map<K, V>!`): a Kotlin `Map` or
        // `MutableMap` is the `java.util.Map` it compiles to, so `HashMap(m)` takes a read-only `m`.
        // A Java spelling of a mapped built-in is that built-in: a Java method's `java.lang.String` result is a
        // `kotlin.String` (`path.toString()` passed for `root: String`).
        val aKotlin = Builtins.kotlinTypeFor(a.qualifiedName)
        if (aKotlin != null && aKotlin == (Builtins.kotlinTypeFor(b.qualifiedName) ?: b.qualifiedName) && aKotlin !in MUTABILITY_FLEXIBLE) {
            decompose(aKotlin, a.typeArguments, b.typeArguments); return
        }
        if (Builtins.javaTypeFor(a.qualifiedName) == b.qualifiedName) {
            decompose(b.qualifiedName, a.typeArguments, b.typeArguments); return
        }
        // Project A onto B's classifier (A's instantiation of the supertype B). Found → decompose those args.
        val proj = service.receiverSupertypeArgs(a.qualifiedName, a.typeArguments, b.qualifiedName)
        if (proj != null) {
            decompose(b.qualifiedName, proj, b.typeArguments); return
        }
        // B is a supertype of A by classifier (args unknown / raw) → satisfiable, no arg constraints.
        if (isClassifierSubtype(a.qualifiedName, b.qualifiedName)) return
        // A function type (`kotlin.FunctionN`) is synthetic, never an indexed "known type", so the check below
        // could never reject one: a `Char` argument stayed applicable to `trimEnd(predicate: (Char) -> Boolean)`,
        // which then won the function-type tiebreak over `trimEnd(vararg chars: Char)`. A known classifier that
        // is not itself a function type and doesn't extend this one (checked just above, so a
        // `class F : (Int) -> Unit` still passes) cannot be one. `kotlin.reflect` is spared: a function
        // reference's `KFunctionN` IS a function type, through a supertype the model may not carry.
        if (TypeRendering.isFunctionType(b.qualifiedName) && !TypeRendering.isFunctionType(a.qualifiedName) &&
            !a.qualifiedName.startsWith("kotlin.reflect.") && service.isKnownType(a.qualifiedName)
        ) {
            hasContradiction = true; return
        }
        // Neither classifier-related nor projectable, and both are genuine known types → A is NOT a subtype of B.
        if (service.isKnownType(a.qualifiedName) && service.isKnownType(b.qualifiedName)) hasContradiction =
            true
    }

    /** The collection built-ins a Java type maps to in its MUTABLE form (`java.util.List` -> `MutableList`): a
     *  read-only target is not that mutable type, which the classifier walk below decides instead. */
    private val MUTABILITY_FLEXIBLE = Builtins.JAVA_TO_KOTLIN.values.filter { it.startsWith("kotlin.collections.Mutable") }.toSet()

    /** Pairwise decomposition of type arguments by their EFFECTIVE variance: the target argument's USE-SITE
     *  projection (`Array<out Number>`, `Comparator<in T>`, `List<*>`) if present, else the classifier [fqn]'s
     *  DECLARATION-SITE variance. An `out` position is covariant (`subᵢ <: supᵢ`), an `in` position
     *  contravariant (`supᵢ <: subᵢ`), an invariant position requires equality (both directions — so
     *  `MutableList<Int>` is NOT `<: MutableList<Number>`), and a star projection accepts any argument (no
     *  constraint). Unknown variance (a plain-Java classifier, or a not-yet-indexed type) defaults to covariant
     *  — a safe over-approximation: it can only miss a contradiction, never invent one. */
    private fun decompose(fqn: String, subArgs: List<TypeRef>, supArgs: List<TypeRef>) {
        val variances = service.classTypeParameterVariance(fqn)
        val n = minOf(subArgs.size, supArgs.size)
        for (i in 0 until n) {
            // The projection only decides the DIRECTION of the constraint below. Once read, it's dropped from
            // both arguments: a variable's value is a type, never a projection. Keeping it bound
            // `Collectors.toList<T>()` against `Collector<in Path, …>` to `T = in Path`, and that leaked into
            // everything off the call (`List<in Path>`, a lambda parameter shown as `it: in Path`). A star is
            // kept: it stands for an unknown argument and has no type to strip down to.
            val sub = unprojected(subArgs[i])
            val sup = supArgs[i]
            val effective = when (val useSite = (sup as? KotlinType)?.projection ?: "") {
                "*" -> continue                          // star: accepts anything → no constraint
                "out", "in" -> useSite                   // a use-site projection overrides declaration variance
                else -> variances.getOrNull(i)
            }
            val target = unprojected(sup)
            when (effective) {
                "in" -> addSubtypeConstraint(
                    target,
                    sub
                )                                     // contravariant
                "" -> {
                    addSubtypeConstraint(sub, target); addSubtypeConstraint(target, sub)
                }   // invariant: equality
                else -> addSubtypeConstraint(
                    sub,
                    target
                )                                     // covariant / unknown
            }
        }
    }

    /** [t] without an `in`/`out` use-site projection; a star projection, or a non-[KotlinType], as is. */
    private fun unprojected(t: TypeRef): TypeRef {
        val k = t as? KotlinType ?: return t
        return if (k.projection == "in" || k.projection == "out") k.withProjection("") else k
    }

    /** Whether [subFqn]'s classifier is [superFqn] or a (Kotlin-mapped) supertype of it — args ignored. */
    private fun isClassifierSubtype(subFqn: String, superFqn: String): Boolean {
        if (subFqn == superFqn) return true
        val target = Builtins.kotlinTypeFor(superFqn) ?: superFqn
        return service.supertypesOf(subFqn)
            .any { (Builtins.kotlinTypeFor(it.qualifiedName) ?: it.qualifiedName) == target }
    }

    /**
     * Re-check a variable's lower bounds against its upper bounds after a bound was added, stopping at the
     * first contradiction (the candidate is already inapplicable).
     *
     * Walks by INDEX over the two lists and re-reads their sizes each round, because decomposing a pair can
     * append further bounds to this same variable: an F-bounded parameter (`<T : Cmp<T>>`) projects its own
     * bound back onto `T`. Those land at the end and this loop covers them, so the nested [addSubtypeConstraint]
     * finds [Var.checking] set and returns: one flat walk per variable instead of a recursive re-walk per bound
     * (which recursed once per added bound until the resolver thread's stack was gone, and re-checked every
     * earlier pair on the way down).
     */
    private fun check(v: Var) {
        if (v.checking) return
        v.checking = true
        try {
            var loDone = 0
            var hiDone = 0
            while (loDone != v.lower.size || hiDone != v.upper.size) {
                val nLo = v.lower.size
                val nHi = v.upper.size
                // Every pair an earlier round didn't cover: new floors × all ceilings, then old floors × new ones.
                for (i in loDone until nLo) for (j in 0 until nHi) if (!checkPair(v.lower[i], v.upper[j])) { declaredViolated(v, j); return }
                for (i in 0 until loDone) for (j in hiDone until nHi) if (!checkPair(v.lower[i], v.upper[j])) { declaredViolated(v, j); return }
                loDone = nLo; hiDone = nHi
            }
        } finally {
            v.checking = false
        }
    }

    /** Note a contradiction against [v]'s declared bound (its upper bound [j] is the one registered). */
    private fun declaredViolated(v: Var, j: Int) {
        if (v.declared != null && v.upper.getOrNull(j) === v.declared) { variableLeftWithoutType = true; variablesLeftWithoutType += v.name }
    }

    /** One floor-vs-ceiling pair; false once it recorded the FIRST contradiction, which ends the walk. */
    private fun checkPair(lo: KotlinType, hi: KotlinType): Boolean {
        if (lo === hi) return true
        val before = hasContradiction
        checkConcrete(lo, hi)
        return before || !hasContradiction
    }

    /** Record [t] as a bound unless an EQUAL one is already there (or the variable is already at [MAX_BOUNDS]),
     *  returning whether the list grew. A repeated bound carries no new information, and admitting it is what
     *  let a self-referential constraint grow a variable's bounds without end, every copy re-triggering the
     *  check that produced it. The count cap catches what deduplication can't: an EXPANSIVE hierarchy
     *  (`Node<T> : Cmp<Node<Node<T>>>`) projects a strictly DEEPER type every round, so each bound is genuinely
     *  new and [check]'s walk would keep finding more forever. Both refusals only lose a redundant or
     *  ever-widening constraint, so they can only miss a contradiction, never invent one. */
    private fun addBound(bounds: ArrayList<KotlinType>, t: KotlinType): Boolean {
        if (bounds.size >= MAX_BOUNDS || bounds.any { sameType(it, t) }) return false
        bounds.add(t)
        return true
    }

    /** Structural type equality, since [KotlinType] has no `equals` and the bounds a decomposition produces are
     *  fresh instances (an index read, a substitution) of types already recorded. */
    private fun sameType(a: KotlinType, b: KotlinType): Boolean {
        if (a === b) return true
        if (a.qualifiedName != b.qualifiedName || a.nullable != b.nullable ||
            a.isTypeParameter != b.isTypeParameter || a.projection != b.projection ||
            a.typeArguments.size != b.typeArguments.size
        ) return false
        for (i in a.typeArguments.indices) {
            val x = a.typeArguments[i]
            val y = b.typeArguments[i]
            if (x is KotlinType && y is KotlinType) {
                if (!sameType(x, y)) return false
            } else if (x !== y) return false
        }
        return true
    }

    /**
     * Fix every variable: its EXACT bound, else the common supertype of its lower bounds (constrained to its
     * upper bounds), else the MOST SPECIFIC of its upper bounds — `emptyList<T>() : List<String>` fixes
     * `T = String` from the upper bound — else [fallback] (typically the declared erased bound). Returns only
     * variables it could fix.
     */
    fun solve(fallback: Map<String, TypeRef> = emptyMap()): Map<String, TypeRef> {
        val out = HashMap<String, TypeRef>()
        for ((name, v) in vars) {
            val fixed: TypeRef? = when {
                v.exact != null -> v.exact
                v.lower.isNotEmpty() -> commonSupertype(v.lower)
                v.upper.isNotEmpty() -> mostSpecificUpperBound(v.upper, v.declared)
                else -> fallback[name]
            }
            if (fixed != null) out[name] = fixed
        }
        return out
    }

    /**
     * The tightest of a return-position variable's upper bounds — the one that is a subtype of every other.
     * A generic method whose result flows into a more specific expected type carries BOTH its declared bound
     * and that expected type as upper bounds (`findViewById(): T` where `T : View`, assigned to `Button`, gets
     * `T <: View` and `T <: Button`); the compiler fixes `T` to the expected `Button` (`Button <: View`), not
     * the declared bound — so the initializer types as `Button`, not `View`. Picking the FIRST upper bound
     * (the declared bound, registered before the constraint) mis-fixed it to the bound and false-flagged the
     * assignment.
     *
     * When the bounds are incomparable the compiler fixes the variable to their INTERSECTION, which the model
     * cannot spell: `fun <T : Foo> foo(): T?` assigned to a `Bar?` is `Foo & Bar`, and `val l: List<Int> =
     * emptyNullableListOfA()!!` (`T : A`) is a `List<A & Int>`. The contextual bound stands in for it then,
     * not the [declared] one: the intersection is a subtype of what the context asked for, so reading it as
     * that type never reports a mismatch the compiler does not. Only where that intersection can have a value,
     * though: an interface on either side, or a nullable contextual bound (`null` is in both). Two unrelated
     * classes (`<T : View> findViewById(): T` assigned to a `String`) intersect in nothing, which the compiler
     * rejects, so the declared bound stays and the mismatch is reported. Otherwise the first bound.
     */
    private fun mostSpecificUpperBound(uppers: List<KotlinType>, declared: KotlinType?): KotlinType {
        uppers.firstOrNull { cand -> uppers.all { it === cand || it.isAssignableFrom(cand) } }?.let { return it }
        val contextual = uppers.filter { it !== declared }
        val pick = contextual.firstOrNull { cand -> contextual.all { it === cand || it.isAssignableFrom(cand) } }
            ?: contextual.firstOrNull() ?: return uppers.first()
        if (declared == null) return pick
        val inhabited = pick.nullable || service.isInterfaceType(declared.qualifiedName) == true ||
            service.isInterfaceType(pick.qualifiedName) == true
        return if (inhabited) pick else uppers.first()
    }

    /** The nearest common supertype of [types] — the fixation of a variable's lower bounds. Agreement is the
     *  common case; otherwise walk the first type's supertype chain for one every other is assignable to
     *  (a bounded least-upper-bound). Nullable if any bound is. */
    private fun commonSupertype(types: List<KotlinType>): KotlinType? {
        val distinct = types.distinctBy { it.qualifiedName }
        val nullable = types.any { it.nullable }
        if (distinct.size == 1) return distinct[0].withNullable(nullable)
        val first = distinct[0]
        val chain =
            listOf(first) + service.supertypesOf(first.qualifiedName).filterIsInstance<KotlinType>()
        return chain.firstOrNull { cand -> distinct.all { cand.isAssignableFrom(it) } }
            ?.withNullable(nullable)
    }

    private companion object {
        /** Cap on decomposition nesting: past any real type's argument nesting, but shallow enough that a
         *  deeply nested (or self-widening) type can't exhaust the caller's stack. */
        const val MAX_DEPTH = 32

        /** Cap on the bounds one variable accumulates on each side; an ordinary call adds one per argument. */
        const val MAX_BOUNDS = 32
    }
}
