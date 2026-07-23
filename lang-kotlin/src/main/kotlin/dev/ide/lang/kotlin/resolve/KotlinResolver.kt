package dev.ide.lang.kotlin.resolve

import dev.ide.lang.kotlin.parse.KotlinParsedFile
import dev.ide.lang.kotlin.symbols.FileContext
import dev.ide.lang.kotlin.symbols.ImportInfo
import dev.ide.lang.kotlin.symbols.KotlinSymbol
import dev.ide.lang.kotlin.symbols.KotlinSymbolService
import dev.ide.lang.kotlin.symbols.KotlinType
import dev.ide.lang.resolve.SymbolOrigin
import dev.ide.lang.resolve.TypeRef
import com.intellij.psi.PsiElement
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.KtParenthesizedExpression

/** The Compose calling-convention status of a code position (see [KotlinResolver.composableContextAt]). */
enum class ComposableContext { COMPOSABLE, NON_COMPOSABLE, UNKNOWN }

/** The suspend calling-convention status of a code position (see [KotlinResolver.suspendContextAt]). */
enum class SuspendContext { SUSPEND, NON_SUSPEND, UNKNOWN }

/**
 * Opt-in counters for the resolver's compute-vs-cache behavior, off by default — when disabled each call
 * site is a single volatile-guarded branch, so there is no production cost. The preview-lowering and
 * completion benchmarks flip [enabled] to attribute cost between type inference and candidate enumeration,
 * and to see how often a result is recomputed rather than served from the per-snapshot cache. Counts are
 * approximate under concurrency (plain longs); the benchmarks drive them single-threaded.
 */
object KotlinResolverStats {
    @Volatile
    var enabled: Boolean = false
    @Volatile
    var inferCalls: Long = 0
    @Volatile
    var inferComputes: Long = 0
    @Volatile
    var callTargetsCalls: Long = 0
    @Volatile
    var callTargetsComputes: Long = 0
    fun reset() {
        inferCalls = 0; inferComputes = 0; callTargetsCalls = 0; callTargetsComputes = 0
    }
}

/**
 * The per-snapshot memo caches a [KotlinResolver] fills — inference, callee, the call's overload set, implicit
 * receivers, and the compose/suspend context. They are pure for a given (immutable) parse, so they can be
 * SHARED across the several resolvers a single keystroke builds over the same snapshot (the analyzer's
 * diagnostics pass and the Compose preview lowerer each construct their own [KotlinResolver]). Sharing the
 * caches — but NOT the resolver's transient per-resolution state ([KotlinResolver.narrowings] /
 * `resolvingCallees`, which each instance keeps to itself) — means the second pass reuses the first's
 * inference/overload work instead of recomputing it cold, without coupling the two passes' transient state.
 * Every engine lane runs on one serialized worker (`EngineScheduler`), so a shared instance is never touched
 * concurrently; plain maps suffice. A fresh instance (the default) is fully standalone, as before.
 */
class KotlinResolverCaches {
    val infer = HashMap<KtExpression, KotlinType?>()
    val callee = HashMap<KtCallExpression, KotlinSymbol?>()
    val callTargets = HashMap<KtCallExpression, List<KotlinSymbol>>()
    val implicitReceivers = HashMap<Int, List<KotlinType>>()
    val composeCtx = HashMap<PsiElement, ComposableContext>()
    val suspendCtx = HashMap<PsiElement, SuspendContext>()

    /** Per-flow-root result of the CFG var-nullability pass: the var references it proves non-null (see
     *  [KotlinVarNullFlow]). Keyed by the analysed body block. */
    val varNonNull = HashMap<PsiElement, Set<PsiElement>>()
}

/**
 * A dependency-tracking frame for the scoring-time caches ([KotlinResolver.scoringCalleeCache] /
 * [KotlinResolver.scoringInferCache]). While overload SCORING is active every nested callee (and every argument /
 * lambda-body inference) is otherwise recomputed once per outer candidate per level — the ∏(candidate) blowup
 * that froze the editor on a deeply nested Compose builder (`Column{Row{Surface{…}}}`). A frame records, for one
 * in-flight [KotlinResolver.resolveCalleeFunction] or [inferType], which OUTER lambda-shape overrides its
 * computation actually consulted ([deps]) — the ONLY per-candidate-varying input, so an entry with no such deps
 * is provably identical across sibling candidates and can be reused. [shadowed] holds the overrides this
 * computation pushed ITSELF (its own lambda's shape, via [KotlinResolver.withLambdaShape]), whose consults are
 * internal and must NOT count as external dependencies.
 */
internal class ScoringDepFrame {
    val deps = HashMap<KtLambdaExpression, KotlinSymbolService.FunctionalShape?>()
    val shadowed = HashSet<KtLambdaExpression>()
}

/** A scoring-time resolution result ([V]) plus the outer lambda-shape overrides its computation depended on (see
 *  [ScoringDepFrame]); reusable while those overrides are unchanged ([KotlinResolver.overridesMatch]). Backs both
 *  the callee cache ([KotlinSymbol?]) and the type-inference cache ([KotlinType?]). */
internal class ScoringEntry<V>(
    val result: V,
    val deps: Map<KtLambdaExpression, KotlinSymbolService.FunctionalShape?>,
)

/**
 * Resolution and the inference subset, computed over the LIVE [KtFile] (the buffer being edited).
 * No `BindingContext`: scopes are assembled from PSI parents, names resolved against the scope chain, and
 * a small declared-type-driven typer covers literals, locals-from-initializers, member/call return types,
 * and constructor calls, enough for multi-level member completion (`a.b().c`). Anything it can't type
 * degrades to null, yielding scope-only completion, never an error.
 *
 * This class holds only the shared state (the parse, the symbol service, and the per-snapshot memo
 * [caches]); the resolution logic lives in phase files as extension functions on it, one file per phase:
 *  - `KotlinTypeInference`: `inferType` and the expression typer.
 *  - `KotlinCallResolution`: callee binding, overload selection, call targets, parameter shapes.
 *  - `KotlinGenericInference`: type-argument inference, unification, applicability / missing-argument checks.
 *  - `KotlinLambdaInference`: expected functional type, lambda receiver and parameter types.
 *  - `KotlinScopeResolution`: locals, implicit receivers, same-file symbols, enclosing-class context.
 *  - `KotlinExtensionResolution`: member extensions in scope for a receiver, delegate-operator availability.
 *  - `KotlinSmartCast`: flow-sensitive narrowing from `is`/`when`/early-exit and the lowerer's push/pop.
 *  - `KotlinDeclarationTyping`: delegated properties, destructuring, loop variables, parameter symbols.
 *  - `KotlinInheritance`: supertype member closure, overridable members, unimplemented-abstract reports.
 *  - `KotlinCallingConvention`: whether a position is `@Composable` or `suspend`.
 *  - `KotlinExpectedType`: the type a return or argument slot demands.
 */
class KotlinResolver(
    internal val ktFile: KtFile,
    internal val parsed: KotlinParsedFile,
    internal val service: KotlinSymbolService,
    /** Memo caches, sharable across the resolvers a single keystroke builds over the same parse snapshot (see
     *  [KotlinResolverCaches]). Defaults to a private instance, so a standalone resolver behaves as before. */
    internal val caches: KotlinResolverCaches = KotlinResolverCaches(),
) {
    val fileContext: FileContext = run {
        val imports = ktFile.importDirectives.mapNotNull { imp ->
            imp.importedFqName?.asString()?.let { ImportInfo(it, imp.aliasName, imp.isAllUnder) }
        }
        FileContext(ktFile.name, ktFile.packageFqName.asString(), imports)
    }

    // --- inference ---

    // Per-snapshot memo caches (held by [caches], sharable across a keystroke's resolvers — see
    // [KotlinResolverCaches]). Inference + callee resolution are pure for a given (immutable) parse, but
    // recursive and heavily re-entered (esp. on deeply nested Compose, where each call walks its whole ancestor
    // scope chain). Caching turns the O(depth² · calls) blowup into O(calls) — the difference between a ~20s and
    // an instant preview. The aliases below keep every existing use site unchanged.
    internal val inferCache get() = caches.infer

    internal val calleeCache get() = caches.callee

    internal val callTargetsCache get() = caches.callTargets

    internal val implicitReceiversCache get() = caches.implicitReceivers

    internal val composeCtxCache get() = caches.composeCtx

    internal val suspendCtxCache get() = caches.suspendCtx

    // Smart-cast narrowings: a stack of `name → narrowed type` scopes the LOWERER pushes while lowering an
    // `if (x is T) { … }` then-branch (or `when (x) { is T -> … }`), so `x`'s members resolve against `T`.
    // [typeOfName] consults it first. Empty during the analyze pass (only the lowerer drives push/pop), so it
    // never affects diagnostics. While a narrowing is active, [inferType]'s cache is bypassed: an expression's
    // type then depends on the flow context, not the expression alone, so a narrowed result must never be
    // cached and served to an unnarrowed query.
    internal val narrowings = ArrayDeque<Map<String, KotlinType>>()

    /** Push a smart-cast narrowing scope (`name → type`). The lowerer balances it with [popNarrowing]. */
    fun pushNarrowing(narrowed: Map<String, KotlinType>) = narrowings.addLast(narrowed)

    /** Pop the innermost narrowing scope pushed by [pushNarrowing]. */
    fun popNarrowing() = narrowings.removeLast()

    // Bidirectional inference: a lambda's expected functional shape, pushed TOP-DOWN from the candidate/callee
    // being evaluated, so the lambda's parameters (`it`/named) type from that shape WITHOUT re-resolving the
    // enclosing call — which is what breaks the re-entrancy that blocks typing a lambda body during overload
    // resolution (`sumOf { it }`, where the overloads differ only by the lambda's RETURN type). [expectedLambdaShape]
    // consults this first; [inferType]'s cache is bypassed while any override is active (a lambda body's type is
    // then context-dependent, exactly like a smart-cast narrowing, so it must not leak into the shared cache).
    internal val lambdaShapeOverrides =
        HashMap<KtLambdaExpression, KotlinSymbolService.FunctionalShape>()

    /** True while overload resolution is SCORING a candidate by typing its lambda body under a pushed shape
     *  ([lambdaShapeOverrides]). The enclosing call is then mid-resolution, so a nested resolution can be
     *  provisional (re-entrancy fallback) or context-dependent; the per-snapshot memo caches suspend their
     *  reads/writes while this holds, so a provisional result never poisons the real (post-scoring) inference. */
    internal val scoringActive: Boolean get() = lambdaShapeOverrides.isNotEmpty()

    // Dependency-tracked scoring-time caches: the regular [calleeCache]/[inferCache] stay bypassed during
    // scoring (a provisional, context-dependent result must not leak post-scoring), but the SAME nested callee
    // (and the SAME argument/lambda-body inference) is otherwise recomputed once per outer candidate per level —
    // the ∏(candidate) blowup that froze the editor, and the allocation storm behind the semantic pass. These
    // collapse it to O(calls) by memoizing each scoring-time [resolveCalleeFunction]/[inferType] keyed by exactly
    // the outer overrides it consults (see [ScoringDepFrame]/[ScoringEntry]). Per-resolver + snapshot-scoped
    // (transient, like [resolvingCallees]); validity re-checked per read via [overridesMatch].
    internal val scoringDepFrames = ArrayDeque<ScoringDepFrame>()
    internal val scoringCalleeCache = HashMap<KtCallExpression, ScoringEntry<KotlinSymbol?>>()
    internal val scoringInferCache = HashMap<KtExpression, ScoringEntry<KotlinType?>>()

    /** Record that a scoring-time computation consulted [lambda]'s (possibly absent) shape override — into every
     *  active frame that did NOT push it itself, so a cached result is keyed by exactly the OUTER overrides it
     *  depends on. Called from the one place [lambdaShapeOverrides] is read ([expectedLambdaShape]). */
    internal fun recordOverrideConsult(lambda: KtLambdaExpression, value: KotlinSymbolService.FunctionalShape?) {
        if (scoringDepFrames.isEmpty()) return
        for (f in scoringDepFrames) if (lambda !in f.shadowed) f.deps.putIfAbsent(lambda, value)
    }

    /** Whether a cached scoring entry's recorded [deps] still hold under the current overrides. Identity match:
     *  a candidate's shape is a fresh instance each scoring, so a changed override never spuriously matches (at
     *  worst forcing a safe recompute), and an absent-vs-present change (null vs a shape) is likewise a miss. */
    internal fun overridesMatch(deps: Map<KtLambdaExpression, KotlinSymbolService.FunctionalShape?>): Boolean {
        for ((lambda, shape) in deps) if (lambdaShapeOverrides[lambda] !== shape) return false
        return true
    }

    /** Propagate a reused entry's [deps] to the active frames — the reusing computation now transitively depends
     *  on whatever that entry did (skipping any override a frame pushed itself). */
    internal fun propagateDeps(deps: Map<KtLambdaExpression, KotlinSymbolService.FunctionalShape?>) {
        for (f in scoringDepFrames) for ((l, s) in deps) if (l !in f.shadowed) f.deps.putIfAbsent(l, s)
    }

    /** Run [body] with [shape] pushed as [lambda]'s expected functional shape (see [lambdaShapeOverrides]),
     *  restoring the prior binding afterwards. */
    internal fun <T> withLambdaShape(
        lambda: KtLambdaExpression,
        shape: KotlinSymbolService.FunctionalShape,
        body: () -> T
    ): T {
        val had = lambdaShapeOverrides.containsKey(lambda)
        val prev = lambdaShapeOverrides.put(lambda, shape)
        // This override belongs to the CURRENT scoring computations — shadow it in every active dep frame so its
        // own consults (typing THIS lambda's params/body) aren't recorded as EXTERNAL deps of those computations.
        for (f in scoringDepFrames) f.shadowed.add(lambda)
        try {
            return body()
        } finally {
            if (had) lambdaShapeOverrides[lambda] = prev!! else lambdaShapeOverrides.remove(lambda)
        }
    }

    internal fun unwrapParens(e: KtExpression?): KtExpression? =
        if (e is KtParenthesizedExpression) unwrapParens(e.expression) else e

    /** Calls currently being resolved: a re-entrancy guard. The scope-aware callee branch consults
     *  [implicitReceiversAt], which resolves enclosing calls' function types via `expectedFunctionTypeFor` →
     *  `resolveCalleeFunction`; without this guard a call whose resolution (transitively) needs its own would
     *  recurse forever (it hangs editor analysis on nested Compose). Re-entry returns null, breaking the cycle. */
    internal val resolvingCallees = HashSet<KtCallExpression>()

    /** Expression-body functions currently having their return type inferred from their body — a re-entrancy
     *  guard for the call-site return-type inference ([inferredReturnTypeForCall]). Without it a mutual
     *  `fun a() = b(); fun b() = a()` (neither declares a return type) would recurse forever; re-entry
     *  returns null, breaking the cycle. */
    internal val inferringReturnBodies = HashSet<PsiElement>()

    /** Expressions currently having their type inferred — a re-entrancy guard for [inferType]. Its cache is
     *  written only AFTER computing, so a cycle that re-enters the SAME expression mid-inference never hits the
     *  cache: a `by`-delegated property (`var x by mutableStateOf(…)`) types its delegate, whose generic-call
     *  argument resolution enumerates same-file properties — itself included — back through [delegatedValueType]
     *  into the same delegate. Re-entry returns null (never cached); the outer inference computes the real type. */
    internal val inferringTypes = HashSet<KtExpression>()

    /** Simple name of a parameter's declared type TEXT (`kotlin.Int` / `List<String>` → `Int` / `List`); null
     *  text → null. Type arguments are dropped (JVM erasure forbids overloading on them, so it's safe). */
    internal fun simpleTypeName(text: String?): String? =
        text?.substringBefore('<')?.substringAfterLast('.')?.trim()?.takeIf { it.isNotEmpty() }

    /** Simple name of an inherited member's parameter [TypeRef] (`kotlin.String` → `String`, a type parameter
     *  keeps its name `T`), for matching against a local parameter's [simpleTypeName]. */
    internal fun paramSimpleName(t: dev.ide.lang.resolve.TypeRef?): String? =
        (t as? KotlinType)?.qualifiedName?.substringAfterLast('.')?.takeIf { it.isNotEmpty() }

    internal fun elementAt(offset: Int): PsiElement? {
        val len = ktFile.textLength
        if (len == 0) return null
        return ktFile.findElementAt(if (offset >= len) len - 1 else offset.coerceAtLeast(0))
    }

}

internal val SOURCE = SymbolOrigin(fromSource = true, file = null)
