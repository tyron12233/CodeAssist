package dev.ide.lang.kotlin.resolve

import com.intellij.psi.PsiElement
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtAnnotatedExpression
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtBreakExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtContinueExpression
import org.jetbrains.kotlin.psi.KtDoWhileExpression
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtForExpression
import org.jetbrains.kotlin.psi.KtFunctionLiteral
import org.jetbrains.kotlin.psi.KtIfExpression
import org.jetbrains.kotlin.psi.KtLabeledExpression
import org.jetbrains.kotlin.psi.KtLoopExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtParenthesizedExpression
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtReturnExpression
import org.jetbrains.kotlin.psi.KtThrowExpression
import org.jetbrains.kotlin.psi.KtTryExpression
import org.jetbrains.kotlin.psi.KtWhenExpression
import org.jetbrains.kotlin.psi.KtWhileExpression

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
    private fun callLiveness(e: KtExpression): Liveness =
        if (isNothingReturning(e)) Liveness.DEAD else Liveness.LIVE

    /** `x ?: <jump>` never falls through the elvis when the left is null (the RHS jumps); but the left may be
     *  non-null, so overall it still completes → LIVE. Only a top-level jump construct deadens (handled above).
     *  Other binaries (assignment, arithmetic, `&&`) complete normally. */
    private fun binaryLiveness(e: KtBinaryExpression): Liveness = Liveness.LIVE

    private fun isNothingReturning(e: KtExpression): Boolean {
        val call = when (e) {
            is KtCallExpression -> e
            is KtDotQualifiedExpression -> e.selectorExpression as? KtCallExpression
            else -> null
        }
        // The common Nothing-returning calls are recognized by NAME — no type inference needed.
        val callee = (call?.calleeExpression as? KtNameReferenceExpression)?.getReferencedName()
        if (callee == "TODO" || callee == "error" || callee == "fail") return true
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
        fun rec(p: PsiElement) {
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

    // ---- definite assignment → use-before-initialization ----

    /**
     * Reads of a local `val`/`var` that is declared WITHOUT an initializer and is DEFINITELY unassigned at the
     * read (Kotlin's VARIABLE_MUST_BE_INITIALIZED / "must be initialized before it is used"). A forward
     * MAY-analysis: a variable is flagged only when NO assignment to it could have run on ANY path to the read,
     * so an incomplete view of assignments can only UNDER-report (a read that might have been assigned is left
     * alone) — never a false positive. A variable assigned inside a loop or a closure is dropped from the
     * analysis entirely (a back-edge or a captured write could have assigned it), and reads are matched to their
     * declaration by binding (an inner shadow of the same name is not confused for the tracked local).
     */
    fun uninitializedReads(body: KtBlockExpression): List<KtNameReferenceExpression> {
        val tracked = HashSet<KtProperty>()
        collectUninitLocals(body, tracked)
        if (tracked.isEmpty()) return emptyList()
        val complex = HashSet<KtProperty>()
        markLoopOrClosureAssigned(body, tracked, complex)
        val candidates = tracked - complex
        if (candidates.isEmpty()) return emptyList()
        val out = ArrayList<KtNameReferenceExpression>()
        flowAssign(body, HashSet(), candidates, out)
        return out
    }

    /** Local `val`/`var` declarations with no initializer/delegate/getter and not `lateinit` — the only ones that
     *  can be read before initialization. */
    private fun collectUninitLocals(root: PsiElement, out: MutableSet<KtProperty>) {
        fun rec(p: PsiElement) {
            if (p is KtProperty && p.parent is KtBlockExpression && !p.hasInitializer() && !p.hasDelegate() && p.getter == null && !p.hasModifier(
                    KtTokens.LATEINIT_KEYWORD
                ) && p.name != null
            ) out.add(p)
            var c = p.firstChild
            while (c != null) {
                rec(c); c = c.nextSibling
            }
        }
        rec(root)
    }

    /** Mark any tracked local assigned inside a loop body or a closure (lambda / local function / object literal)
     *  as "complex" — a back-edge or a deferred/captured write could have initialized it, so we don't flag it. */
    private fun markLoopOrClosureAssigned(
        root: PsiElement, tracked: Set<KtProperty>, complex: MutableSet<KtProperty>
    ) {
        fun rec(p: PsiElement, inLoopOrClosure: Boolean) {
            val here =
                inLoopOrClosure || p is KtLoopExpression || p is KtFunctionLiteral || p is KtNamedFunction
            if (here && p is KtBinaryExpression && p.operationToken in ASSIGN_TOKENS) {
                assignedLocal(p.left, tracked)?.let { complex.add(it) }
            }
            var c = p.firstChild
            // The root itself isn't a loop/closure; nested ones flip `here` on for their subtree.
            while (c != null) {
                rec(c, if (p === root) inLoopOrClosure else here); c = c.nextSibling
            }
        }
        rec(root, false)
    }

    /** Thread the definitely-assigned set through [element] in execution order, recording a read of a candidate
     *  local that is not yet assigned. Returns the assigned set after normal completion. Branch joins UNION the
     *  per-branch results (a variable assigned on SOME path is treated as possibly-assigned → not flagged). */
    private fun flowAssign(
        element: PsiElement?,
        inSet: Set<KtProperty>,
        candidates: Set<KtProperty>,
        out: MutableList<KtNameReferenceExpression>,
    ): Set<KtProperty> {
        when (val e = if (element is KtExpression) unwrap(element) else element) {
            null -> return inSet
            // Don't descend into closures / nested type bodies: a lambda/local-fun/object body runs at an unknown
            // time, so we neither record its reads nor trust its assignments here (loop/closure assignments are
            // excluded via markLoopOrClosureAssigned).
            is KtFunctionLiteral, is KtNamedFunction, is KtClassOrObject -> return inSet
            is KtBlockExpression -> {
                var s = inSet
                for (st in e.statements) s = flowAssign(st, s, candidates, out)
                return s
            }

            is KtProperty -> return flowAssign(
                e.initializer, inSet, candidates, out
            ) // a local decl with initializer is handled by collect (excluded); a nested one just evaluates its RHS
            is KtBinaryExpression -> when {
                e.operationToken in ASSIGN_TOKENS -> {
                    // Evaluate the RHS first (read before the assignment takes effect), then mark the LHS assigned.
                    val afterRhs = flowAssign(e.right, inSet, candidates, out)
                    val target = assignedLocal(e.left, candidates)
                    // A compound assign (`x += …`) also READS the LHS, but that is invalid on an uninitialized
                    // val/var anyway; we conservatively treat it as an assignment only (record no read).
                    return if (target != null) afterRhs + target else flowAssign(
                        e.left, afterRhs, candidates, out
                    )
                }

                e.operationToken == KtTokens.ANDAND || e.operationToken == KtTokens.OROR || e.operationToken == KtTokens.ELVIS -> {
                    // Short-circuit: the RHS is evaluated conditionally, so its assignments are NOT definite. Walk
                    // it for reads (in the LHS-decided state), but return only the LHS's assignments.
                    val l = flowAssign(e.left, inSet, candidates, out)
                    flowAssign(e.right, l, candidates, out)
                    return l
                }

                else -> return flowAssign(
                    e.right, flowAssign(e.left, inSet, candidates, out), candidates, out
                )
            }

            is KtTryExpression -> {
                // A catch/finally can be entered before any `try`-body assignment ran, so a catch starts from the
                // ENTRY set (not the try's out). After the whole try, definitely-assigned = intersection over the
                // paths that can REACH there (a catch that always returns/throws contributes nothing); a `finally`
                // always runs, so its assignments are added unconditionally.
                val tryOut = flowAssign(e.tryBlock, inSet, candidates, out)
                val parts = ArrayList<Set<KtProperty>>()
                if (liveness(e.tryBlock) != Liveness.DEAD) parts.add(tryOut)
                e.catchClauses.forEach { cc ->
                    val co = flowAssign(cc.catchBody, inSet, candidates, out)
                    if (liveness(cc.catchBody) != Liveness.DEAD) parts.add(co)
                }
                var res = if (parts.isEmpty()) inSet else parts.reduce { a, b -> a intersect b }
                e.finallyBlock?.finalExpression?.let {
                    res = res + flowAssign(it, inSet, candidates, out)
                }
                return res
            }

            is KtIfExpression -> {
                val afterCond = flowAssign(e.condition, inSet, candidates, out)
                val thenOut = flowAssign(e.then, afterCond, candidates, out)
                val elseOut = if (e.`else` != null) flowAssign(
                    e.`else`, afterCond, candidates, out
                ) else afterCond
                // A branch that always jumps (return/throw) doesn't reach the code after the `if`, so only the
                // surviving branch(es) define what is assigned there (`if (c) x = 1 else return` ⇒ x assigned after).
                val thenLive = liveness(e.then) != Liveness.DEAD
                val elseLive = if (e.`else` != null) liveness(e.`else`) != Liveness.DEAD else true
                return when {
                    thenLive && elseLive -> thenOut intersect elseOut
                    thenLive -> thenOut
                    elseLive -> elseOut
                    else -> afterCond // both branches jump → code after is unreachable; set is moot
                }
            }

            is KtWhenExpression -> {
                val afterSubject = flowAssign(e.subjectExpression, inSet, candidates, out)
                // Conservative: don't assume any branch ran (a no-match path keeps the entry set). Each branch's
                // body reads see the entry set.
                e.entries.forEach { flowAssign(it.expression, afterSubject, candidates, out) }
                return afterSubject
            }

            is KtWhileExpression -> {
                val afterCond = flowAssign(e.condition, inSet, candidates, out)
                flowAssign(
                    e.body, afterCond, candidates, out
                ) // body reads see the pre-loop set (first iteration)
                return afterCond                               // may run zero times → nothing new definitely assigned
            }

            is KtDoWhileExpression -> {
                val afterBody = flowAssign(e.body, inSet, candidates, out)
                return flowAssign(
                    e.condition, afterBody, candidates, out
                ) // body runs at least once
            }

            is KtForExpression -> {
                val afterRange = flowAssign(e.loopRange, inSet, candidates, out)
                flowAssign(e.body, afterRange, candidates, out)
                return afterRange
            }

            is KtNameReferenceExpression -> {
                val decl = boundLocal(e)
                if (decl != null && decl in candidates && decl !in inSet) out.add(e)
                return inSet
            }

            else -> {
                // Default: recurse into ALL children left-to-right, threading the assigned set — so reads nested
                // inside argument lists / qualified expressions / etc. (not just direct KtExpression children) are
                // recorded in evaluation order.
                var s: Set<KtProperty> = inSet
                var c = e.firstChild
                while (c != null) {
                    s = flowAssign(c, s, candidates, out); c = c.nextSibling
                }
                return s
            }
        }
    }

    /** The candidate local a simple assignment target denotes (`x = …`), or null for `this.x`/`a.b`/non-tracked. */
    private fun assignedLocal(lhs: KtExpression?, candidates: Set<KtProperty>): KtProperty? {
        val ref = unwrap(lhs) as? KtNameReferenceExpression ?: return null
        return boundLocal(ref)?.takeIf { it in candidates }
    }

    /** The nearest local `val`/`var` property [ref] binds to (in an enclosing block), or null if it resolves to a
     *  parameter / member / unknown. Matches by scope (a shadowing inner local wins) and walks THROUGH closure
     *  boundaries, since a lambda/local function captures outer locals (needed to see a captured `x = 1`). Stops
     *  at a class body (a member is not a local). */
    private fun boundLocal(ref: KtNameReferenceExpression): KtProperty? {
        val name = ref.getReferencedName()
        val offset = ref.textRange.startOffset
        var node: PsiElement? = ref.parent
        while (node != null) {
            when (node) {
                is KtBlockExpression -> node.statements.firstOrNull {
                    it is KtProperty && it.name == name && it.textRange.endOffset <= offset
                }?.let { return it as KtProperty }

                is KtClassOrObject -> return null // a member scope: not a local
            }
            node = node.parent
        }
        return null
    }

    private companion object {
        val ASSIGN_TOKENS = setOf(
            KtTokens.EQ,
            KtTokens.PLUSEQ,
            KtTokens.MINUSEQ,
            KtTokens.MULTEQ,
            KtTokens.DIVEQ,
            KtTokens.PERCEQ,
        )
    }
}
