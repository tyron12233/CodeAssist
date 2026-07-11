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

    /** Run [body] with [shape] pushed as [lambda]'s expected functional shape (see [lambdaShapeOverrides]),
     *  restoring the prior binding afterwards. */
    internal fun <T> withLambdaShape(
        lambda: KtLambdaExpression,
        shape: KotlinSymbolService.FunctionalShape,
        body: () -> T
    ): T {
        val had = lambdaShapeOverrides.containsKey(lambda)
        val prev = lambdaShapeOverrides.put(lambda, shape)
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
