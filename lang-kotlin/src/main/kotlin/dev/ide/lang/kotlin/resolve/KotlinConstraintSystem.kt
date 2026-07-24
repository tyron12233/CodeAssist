package dev.ide.lang.kotlin.resolve

import dev.ide.lang.kotlin.symbols.Builtins
import dev.ide.lang.kotlin.symbols.KotlinSymbolService
import dev.ide.lang.kotlin.symbols.KotlinType
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
 * FIDELITY: the [KotlinType] model carries no use-site variance projections, so generic subtyping is
 * decomposed COVARIANTLY (`Foo<A> <: Foo<B>` ⇒ `A <: B`). That is exact for the read-only collection types
 * inference rides (List/Set/Map/Iterable are declaration-site `out`) and a safe over-approximation elsewhere:
 * it can only MISS a contradiction (degrading to the caller's tiebreak), never invent one, so it never rejects
 * a call the compiler accepts. Faithful invariant/contravariant checking would need projections the model
 * doesn't retain.
 */
internal class KotlinConstraintSystem(private val service: KotlinSymbolService) {

    private class Var(val name: String) {
        val lower = ArrayList<KotlinType>()
        val upper = ArrayList<KotlinType>()
        var exact: KotlinType? = null
    }

    private val vars = LinkedHashMap<String, Var>()

    /** True once an unsatisfiable constraint was seen — the candidate is INAPPLICABLE. */
    var hasContradiction = false
        private set

    val isEmpty: Boolean get() = vars.isEmpty()

    fun registerVariable(name: String, upperBound: KotlinType? = null) {
        val v = vars.getOrPut(name) { Var(name) }
        if (upperBound != null && !upperBound.isTypeParameter && upperBound.qualifiedName != "kotlin.Any") {
            v.upper.add(upperBound); check(v)
        }
    }

    private fun variable(t: KotlinType?): Var? =
        if (t != null && t.isTypeParameter) vars[t.qualifiedName] else null

    /** Pin a variable exactly (an explicit type argument). */
    fun fix(name: String, type: KotlinType) {
        vars[name]?.let { it.exact = type; check(it) }
    }

    /** Constrain `sub <: sup`, decomposing until it reaches a variable bound or a concrete check. */
    fun addSubtypeConstraint(sub: TypeRef?, sup: TypeRef?) {
        val a = sub as? KotlinType ?: return
        val b = sup as? KotlinType ?: return
        val bv = variable(b)
        val av = variable(a)
        when {
            bv != null && av != null -> {} // variable <: variable: skip (rare; both stay open, fixation handles)
            bv != null -> {
                bv.lower.add(a); check(bv)
            }          // b ≥ a
            av != null -> {
                av.upper.add(b); check(av)
            }          // a ≤ b
            else -> checkConcrete(
                a,
                b
            ) // both concrete → decompose + check
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
        // Project A onto B's classifier (A's instantiation of the supertype B). Found → decompose those args.
        val proj = service.receiverSupertypeArgs(a.qualifiedName, a.typeArguments, b.qualifiedName)
        if (proj != null) {
            decompose(b.qualifiedName, proj, b.typeArguments); return
        }
        // B is a supertype of A by classifier (args unknown / raw) → satisfiable, no arg constraints.
        if (isClassifierSubtype(a.qualifiedName, b.qualifiedName)) return
        // Neither classifier-related nor projectable, and both are genuine known types → A is NOT a subtype of B.
        if (service.isKnownType(a.qualifiedName) && service.isKnownType(b.qualifiedName)) hasContradiction =
            true
    }

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
            val sub = subArgs[i]
            val sup = supArgs[i]
            val effective = when (val useSite = (sup as? KotlinType)?.projection ?: "") {
                "*" -> continue                          // star: accepts anything → no constraint
                "out", "in" -> useSite                   // a use-site projection overrides declaration variance
                else -> variances.getOrNull(i)
            }
            when (effective) {
                "in" -> addSubtypeConstraint(
                    sup,
                    sub
                )                                     // contravariant
                "" -> {
                    addSubtypeConstraint(sub, sup); addSubtypeConstraint(sup, sub)
                }   // invariant: equality
                else -> addSubtypeConstraint(
                    sub,
                    sup
                )                                     // covariant / unknown
            }
        }
    }

    /** Whether [subFqn]'s classifier is [superFqn] or a (Kotlin-mapped) supertype of it — args ignored. */
    private fun isClassifierSubtype(subFqn: String, superFqn: String): Boolean {
        if (subFqn == superFqn) return true
        val target = Builtins.kotlinTypeFor(superFqn) ?: superFqn
        return service.supertypesOf(subFqn)
            .any { (Builtins.kotlinTypeFor(it.qualifiedName) ?: it.qualifiedName) == target }
    }

    /** Re-check a variable's lower bounds against its upper bounds after a bound was added. */
    private fun check(v: Var) {
        val ceilings = (v.upper + listOfNotNull(v.exact))
        val floors = (v.lower + listOfNotNull(v.exact))
        for (lo in floors) for (hi in ceilings) if (lo !== hi) {
            val before = hasContradiction
            checkConcrete(lo, hi)
            if (hasContradiction && !before) return
        }
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
                v.upper.isNotEmpty() -> mostSpecificUpperBound(v.upper)
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
     * assignment. Falls back to the first when the bounds are incomparable (unrelated types — no single
     * greatest lower bound is modeled), matching the prior behavior in that case.
     */
    private fun mostSpecificUpperBound(uppers: List<KotlinType>): KotlinType =
        uppers.firstOrNull { cand -> uppers.all { it === cand || it.isAssignableFrom(cand) } } ?: uppers.first()

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
}
