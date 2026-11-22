package dev.ide.lang.kotlin.resolve

import dev.ide.kotlin.syntax.psi.KtAnnotatedExpression
import dev.ide.kotlin.syntax.psi.KtBinaryExpression
import dev.ide.kotlin.syntax.psi.KtBlockExpression
import dev.ide.kotlin.syntax.psi.KtBreakExpression
import dev.ide.kotlin.syntax.psi.KtCallExpression
import dev.ide.kotlin.syntax.psi.KtClassOrObject
import dev.ide.kotlin.syntax.psi.KtContinueExpression
import dev.ide.kotlin.syntax.psi.KtDoWhileExpression
import dev.ide.kotlin.syntax.psi.KtDotQualifiedExpression
import dev.ide.kotlin.syntax.psi.KtElement
import dev.ide.kotlin.syntax.psi.KtExpression
import dev.ide.kotlin.syntax.psi.KtForExpression
import dev.ide.kotlin.syntax.psi.KtFunctionLiteral
import dev.ide.kotlin.syntax.psi.KtIfExpression
import dev.ide.kotlin.syntax.psi.KtLabeledExpression
import dev.ide.kotlin.syntax.psi.KtLambdaExpression
import dev.ide.kotlin.syntax.psi.KtLoopExpression
import dev.ide.kotlin.syntax.psi.KtNameReferenceExpression
import dev.ide.kotlin.syntax.psi.KtNamedFunction
import dev.ide.kotlin.syntax.psi.KtParenthesizedExpression
import dev.ide.kotlin.syntax.psi.KtQualifiedExpression
import dev.ide.kotlin.syntax.psi.KtReturnExpression
import dev.ide.kotlin.syntax.psi.KtThrowExpression
import dev.ide.kotlin.syntax.psi.KtTokens
import dev.ide.kotlin.syntax.psi.KtTryExpression
import dev.ide.kotlin.syntax.psi.KtWhenExpression
import dev.ide.kotlin.syntax.psi.KtWhileExpression

/**
 * A per-body CONTROL-FLOW / reachability analysis — the data-flow half of the flow layer, computing whether
 * control can complete a construct normally (fall through) or always transfers out (return/throw/break/continue,
 * a `Nothing`-returning call, an infinite loop). It is a structured abstract interpretation over the PSI: a
 * recursive evaluation that composes each construct's liveness (the standard data-flow lattice over the
 * control-flow graph, without materialising the graph). Used for precise "missing return" (does EVERY path
 * return?) and precise "unreachable code" (a statement whose entry is dead), replacing the old heuristics
 * (which backed off as soon as any `return` appeared, and only saw top-level terminator statements).
 *
 * ## Three-valued and conservative
 * [Liveness] is LIVE (control can fall through — a DEFINITE conclusion), DEAD (control never falls through — a
 * DEFINITE conclusion), or UNKNOWN (can't decide). Consumers that FLAG (missing-return) act only on a definite
 * verdict (LIVE to flag a missing return; a DEAD/UNKNOWN body is never flagged), so an incompleteness in the
 * analysis (a `when` whose exhaustiveness we can't judge, an unmodelled construct) degrades to UNKNOWN and
 * under-reports — it never produces a false positive. Joins take LIVE if any branch is LIVE, DEAD only if all
 * are DEAD, else UNKNOWN.
 */
internal enum class Liveness { LIVE, DEAD, UNKNOWN }

internal class KotlinControlFlow(private val resolver: KotlinResolver) {

    /** Whether control can complete [element] normally (fall through). See the class header for the contract. */
    /** Scope calls with no receiver that invoke their lambda once, unconditionally. */
    private val BARE_SCOPE_CALLS = setOf("run", "synchronized", "with", "runBlocking")

    /** The same, called on a DOT receiver (`x.run { }`); `x?.run { }` is a different node and never matches. */
    private val DOT_SCOPE_CALLS = setOf("run", "let", "also", "apply", "use", "withLock")

    fun liveness(element: KtExpression?): Liveness = when (val e = unwrap(element)) {
        null -> Liveness.LIVE
        is KtReturnExpression, is KtThrowExpression, is KtBreakExpression, is KtContinueExpression -> Liveness.DEAD
        is KtBlockExpression -> blockLiveness(e)
        is KtIfExpression -> ifLiveness(e)
        is KtWhenExpression -> whenLiveness(e)
        is KtWhileExpression -> whileLiveness(e)
        is KtDoWhileExpression -> doWhileLiveness(e)
        is KtForExpression -> Liveness.LIVE // the loop body may run zero times, then the loop completes normally
        is KtTryExpression -> tryLiveness(e)
        is KtCallExpression -> callLiveness(e)
        is KtDotQualifiedExpression -> callLiveness(e) // a trailing `foo.bar()` may be `Nothing`-returning
        is KtBinaryExpression -> binaryLiveness(e)
        // `val a = try { … } finally { return x }`: a local's initializer runs as part of the statement, so a
        // jump inside it ends the statement too.
        is dev.ide.kotlin.syntax.psi.KtProperty -> if (e.isLocal) liveness(e.initializer) else Liveness.LIVE
        // `null!!` always throws.
        is dev.ide.kotlin.syntax.psi.KtPostfixExpression ->
            if (e.operationToken == KtTokens.EXCLEXCL && unwrap(e.baseExpression)?.text == "null") Liveness.DEAD
            else liveness(e.baseExpression)
        else -> Liveness.LIVE // literals, refs, ordinary expressions complete normally
    }

    /** The liveness of a block = the liveness after its last statement, folding in evaluation order: once a
     *  statement is DEAD, the rest are unreachable (their liveness no longer contributes). */
    fun blockLiveness(block: KtBlockExpression): Liveness {
        var cur = Liveness.LIVE
        for (st in block.statements) {
            when (cur) {
                Liveness.DEAD -> return Liveness.DEAD          // rest unreachable; block never falls through
                Liveness.UNKNOWN -> return Liveness.UNKNOWN    // can't be sure the rest is reached → give up (safe)
                Liveness.LIVE -> cur = liveness(st as? KtExpression)
            }
        }
        return cur
    }

    /** The statements in [block] whose ENTRY is dead — i.e. every statement after the first one that never
     *  completes normally. Contiguous (once dead, all subsequent are), so callers can render one range. */
    fun deadStatements(block: KtBlockExpression): List<KtExpression> {
        var cur = Liveness.LIVE
        val out = ArrayList<KtExpression>()
        for (st in block.statements) {
            val e = st ?: continue
            when (cur) {
                Liveness.DEAD -> out.add(e)                    // unreachable
                Liveness.UNKNOWN -> return out                 // can't be sure → stop marking (never over-report)
                Liveness.LIVE -> cur = liveness(e)
            }
        }
        return out
    }

    private fun ifLiveness(e: KtIfExpression): Liveness {
        val thenL = liveness(e.then)
        // No `else` → the not-taken path falls straight through, so the `if` as a whole is LIVE.
        val elseL = if (e.`else` == null) Liveness.LIVE else liveness(e.`else`)
        return join(thenL, elseL)
    }

    private fun whenLiveness(e: KtWhenExpression): Liveness {
        val branchLs = e.entries.map { liveness(it.expression) }
        val hasElse = e.entries.any { it.isElse }
        if (hasElse) return joinAll(branchLs)
        // No `else`: a LIVE branch means control can fall through. If every branch is DEAD, the whole thing is
        // DEAD only when the `when` is exhaustive (no fall-through no-match path) — which we don't decide here, so
        // UNKNOWN (a non-exhaustive all-DEAD `when` really falls through, an exhaustive one doesn't; back off).
        return if (branchLs.any { it == Liveness.LIVE }) Liveness.LIVE else Liveness.UNKNOWN
    }

    /** `while (true) { … }` with no `break` targeting it never completes → DEAD; any other `while` completes when
     *  its condition turns false (or ran zero times) → LIVE. */
    private fun whileLiveness(e: KtWhileExpression): Liveness =
        if (isTrueLiteral(e.condition) && !hasBreak(e.body)) Liveness.DEAD else Liveness.LIVE

    private fun doWhileLiveness(e: KtDoWhileExpression): Liveness =
        if (isTrueLiteral(e.condition) && !hasBreak(e.body)) Liveness.DEAD else Liveness.LIVE

    private fun tryLiveness(e: KtTryExpression): Liveness {
        val fin = e.finallyBlock?.finalExpression
        if (fin != null && liveness(fin) == Liveness.DEAD) return Liveness.DEAD // a finally that jumps wins
        val branches = ArrayList<Liveness>()
        branches += liveness(e.tryBlock)
        e.catchClauses.forEach { branches += liveness(it.catchBody) }
        return joinAll(branches)
    }

    /** A call is DEAD when it provably returns `Nothing` (`TODO()`/`error()`/`fail()`, or an inferred `Nothing`
     *  type); otherwise it completes normally → LIVE. (A bare call used as a statement does NOT satisfy a
     *  value-returning function's return requirement — that is exactly the missing-return we want to flag.) */
    private fun callLiveness(e: KtExpression): Liveness {
        if (isNothingReturning(e)) return Liveness.DEAD
        // An inline SCOPE call runs its lambda exactly once and unconditionally, so a `return` inside that
        // lambda is a non-local return from the enclosing function -- the call cannot fall through:
        //
        //     private fun loaded(): MutableMap<String, Int> {
        //         counts?.let { return it }
        //         synchronized(lock) { … ; return map }
        //     }                                          // <- "a 'return' expression required"
        //
        // Both shapes in this repository are that one. No inference is involved (the note below explains why
        // that matters here): a name match, then the lambda body's own liveness, which this pass walks anyway.
        val scopeBody = scopeCallLambdaBody(e)
        if (scopeBody != null) {
            if (liveness(scopeBody) != Liveness.DEAD) return Liveness.LIVE
            // `run { return@run 1 }` ends the LAMBDA, not the function, and the call then completes. Which jump
            // made the body dead is not tracked, so a labeled return anywhere in it leaves the call undecided.
            return if (hasReturn(scopeBody, labeled = true)) Liveness.UNKNOWN else Liveness.DEAD
        }
        // Any other call handed a lambda that returns from the enclosing function, or ends in a `throw`:
        //
        //     suspend fun example(): String {
        //         mutex.withLock { return "Done" }
        //     }
        //
        // A `return` there is only legal because the callee is inline, and whether the call can still fall
        // through is decided by the callee's CONTRACT: `withLock`, like every `callsInPlace(EXACTLY_ONCE)`
        // function, cannot, while `forEach { return x }` can (an empty list). Contracts are not read here, so
        // the call is undecided, which no consumer flags on. A syntactic scan, never the lambda's liveness: that
        // would infer the calls inside every builder lambda (see [isNothingReturning]).
        if (lambdaArgumentBodies(e).any { hasReturn(it, labeled = false) || endsInThrow(it) }) return Liveness.UNKNOWN
        return Liveness.LIVE
    }

    /** The bodies of the lambdas [e] (a call, or a qualified call) is passed. */
    private fun lambdaArgumentBodies(e: KtExpression): List<KtExpression> {
        val call = when (e) {
            is KtCallExpression -> e
            is KtQualifiedExpression -> e.selectorExpression as? KtCallExpression
            else -> null
        } ?: return emptyList()
        return (call.lambdaArguments.map { it.getLambdaExpression() } +
            call.valueArguments.map { it.getArgumentExpression() as? KtLambdaExpression })
            .mapNotNull { it?.bodyExpression }
    }

    /** Whether [body] holds a `return` that is [labeled] (`return@x`) or not, outside any nested named function
     *  (whose returns are its own). A nested lambda is searched: an unlabeled return in it still leaves the
     *  enclosing function. */
    private fun hasReturn(body: KtExpression, labeled: Boolean): Boolean {
        var found = false
        fun rec(p: KtElement) {
            if (found) return
            if (p is KtReturnExpression && (p.getLabelName() != null) == labeled) { found = true; return }
            if (p is KtNamedFunction || p is KtClassOrObject) return
            var c = p.firstChild
            while (c != null && !found) { rec(c); c = c.nextSibling }
        }
        rec(body)
        return found
    }

    private fun endsInThrow(body: KtExpression): Boolean =
        unwrap((body as? KtBlockExpression)?.statements?.lastOrNull() as? KtExpression ?: body) is KtThrowExpression

    /**
     * The lambda body of an inline scope call that is guaranteed to run exactly once — `run`/`synchronized`/
     * `with`/`runBlocking` unqualified, or `run`/`let`/`also`/`apply`/`use` on a DOT receiver.
     *
     * A `?.` receiver is a [KtSafeQualifiedExpression], a different node, so the conditional form never
     * reaches here. Anything else (a builder, a user function taking a lambda) is not known to invoke it and
     * is left LIVE.
     */
    private fun scopeCallLambdaBody(e: KtExpression): KtExpression? {
        val (call, qualified) = when (e) {
            is KtCallExpression -> e to false
            is KtDotQualifiedExpression -> (e.selectorExpression as? KtCallExpression ?: return null) to true
            else -> return null
        }
        val name = (call.calleeExpression as? KtNameReferenceExpression)?.getReferencedName() ?: return null
        val known = if (qualified) name in DOT_SCOPE_CALLS else name in BARE_SCOPE_CALLS
        if (!known) return null
        val lambda = call.lambdaArguments.lastOrNull()?.getLambdaExpression()
            ?: call.valueArguments.lastOrNull()?.getArgumentExpression() as? KtLambdaExpression
        return lambda?.bodyExpression
    }

    /** `x ?: <jump>` never falls through the elvis when the left is null (the RHS jumps); but the left may be
     *  non-null, so overall it still completes → LIVE. Only a top-level jump construct deadens (handled above).
     *  Other binaries (assignment, arithmetic, `&&`) complete normally. */
    /** The LEFT operand always runs, so a jump there ends the whole expression (`(return true) && x`,
     *  `(if (c) return 1 else null) ?: return 0`). An elvis whose left side can only be `null` (a `null` literal,
     *  or an `if` whose live branches are all `null`) always runs its right side too. Anything else completes
     *  normally as far as this pass can tell. */
    private fun binaryLiveness(e: KtBinaryExpression): Liveness {
        val left = liveness(e.left)
        if (left == Liveness.DEAD) return Liveness.DEAD
        if (e.operationToken == KtTokens.ELVIS && left == Liveness.LIVE && alwaysNull(e.left)) return liveness(e.right)
        return Liveness.LIVE
    }

    /** Whether [e] can only produce `null`: the literal, or an `if` whose branches are each `null` or a jump. */
    private fun alwaysNull(e: KtExpression?): Boolean = when (val u = unwrap(e)) {
        null -> false
        is KtIfExpression -> u.`else` != null && listOf(u.then, u.`else`).all { b ->
            liveness(b) == Liveness.DEAD || alwaysNull(lastValue(b))
        }
        is KtBlockExpression -> alwaysNull(lastValue(u))
        // `run { null }`, `x.let { return it }`, `x?.let { return it }`: a call that yields its lambda's result is
        // `Nothing?` when that lambda's body ends in `null` or never completes, and a `?.` call adds only `null`.
        is KtCallExpression, is KtQualifiedExpression -> lambdaResultBody(u)?.let { body ->
            // A labeled `return@run v` gives the call another value.
            !hasReturn(body, labeled = true) && (liveness(body) == Liveness.DEAD || alwaysNull(lastValue(body)))
        } == true
        else -> u.text == "null"
    }

    /** The lambda body whose value [e] yields: `run { }`/`with(x) { }` unqualified, `run`/`let` on a `.` or `?.`
     *  receiver (`also`/`apply` yield the receiver instead). Null for any other call. */
    private fun lambdaResultBody(e: KtExpression): KtExpression? {
        val (call, qualified) = when (e) {
            is KtCallExpression -> e to false
            is KtQualifiedExpression -> (e.selectorExpression as? KtCallExpression ?: return null) to true
            else -> return null
        }
        val name = (call.calleeExpression as? KtNameReferenceExpression)?.getReferencedName() ?: return null
        val yields = if (qualified) name == "run" || name == "let" else name == "run" || name == "with"
        if (!yields) return null
        val lambda = call.lambdaArguments.lastOrNull()?.getLambdaExpression()
            ?: call.valueArguments.lastOrNull()?.getArgumentExpression() as? KtLambdaExpression
        return lambda?.bodyExpression
    }

    private fun lastValue(e: KtExpression?): KtExpression? =
        (unwrap(e) as? KtBlockExpression)?.statements?.lastOrNull() as? KtExpression ?: unwrap(e)

    private fun isNothingReturning(e: KtExpression): Boolean {
        val call = when (e) {
            is KtCallExpression -> e
            is KtDotQualifiedExpression -> e.selectorExpression as? KtCallExpression
            else -> null
        }
        // The common Nothing-returning calls are recognized by NAME — no type inference needed. Only for an
        // UNQUALIFIED call, though: `kotlin.error`/`TODO`/`kotlin.test.fail` are top-level functions, so a
        // QUALIFIED `receiver.error(...)` (`ConsoleUI.error("…")`) is a DIFFERENT member that does NOT return
        // Nothing — matching it by name flags the following statement as falsely unreachable. A genuine
        // Nothing-returning member is still caught by the precise inference below.
        val qualified = e is KtDotQualifiedExpression
        val callee = (call?.calleeExpression as? KtNameReferenceExpression)?.getReferencedName()
        if (!qualified && (callee == "TODO" || callee == "error" || callee == "fail")) return true
        // A call taking a lambda is a builder/scope call (Compose's `Column { … }`, `Surface { … }`, `remember
        // { … }`): treat it as non-Nothing WITHOUT inferring its type. This is the hot fix — `inferType` on a
        // deeply-nested Compose builder statement drives exponential overload+lambda RE-inference (the inference
        // cache is bypassed during overload scoring), which a CPU profile showed as the entry point of a
        // multi-minute editor freeze on Compose files. Skipping it is a benign dead-code false-NEGATIVE (a
        // `run { throw }` won't flag following code unreachable), never a wrong diagnostic. Leaf calls with no
        // lambda (`Text(...)`, `Spacer(...)`) still infer precisely below — those are shallow + memoized.
        if (call != null && (call.lambdaArguments.isNotEmpty() ||
                call.valueArguments.any { it.getArgumentExpression() is KtLambdaExpression })
        ) return false
        return resolver.inferType(e)?.qualifiedName == "kotlin.Nothing"
    }

    private fun isTrueLiteral(cond: KtExpression?): Boolean = unwrap(cond)?.text?.trim() == "true"

    /** Whether [body] contains a `break` that targets the immediately enclosing loop — scanning stops at a nested
     *  loop (its breaks target it) or a lambda/local function (a break can't cross that boundary). */
    private fun hasBreak(body: KtExpression?): Boolean {
        var found = false
        fun rec(p: KtElement) {
            if (found) return
            when (p) {
                is KtBreakExpression -> {
                    found = true; return
                }

                is KtLoopExpression, is KtFunctionLiteral, is KtNamedFunction -> return // a nested boundary
            }
            var c = p.firstChild
            while (c != null && !found) {
                rec(c); c = c.nextSibling
            }
        }
        if (body != null) rec(body)
        return found
    }

    private fun join(a: Liveness, b: Liveness): Liveness = when {
        a == Liveness.LIVE || b == Liveness.LIVE -> Liveness.LIVE
        a == Liveness.DEAD && b == Liveness.DEAD -> Liveness.DEAD
        else -> Liveness.UNKNOWN
    }

    private fun joinAll(xs: List<Liveness>): Liveness =
        if (xs.isEmpty()) Liveness.LIVE else xs.reduce { a, b -> join(a, b) }

    private fun unwrap(e: KtExpression?): KtExpression? = when (e) {
        is KtParenthesizedExpression -> unwrap(e.expression)
        is KtAnnotatedExpression -> unwrap(e.baseExpression)
        is KtLabeledExpression -> unwrap(e.baseExpression)
        else -> e
    }
}
