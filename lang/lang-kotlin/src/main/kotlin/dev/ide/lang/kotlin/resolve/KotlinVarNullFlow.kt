package dev.ide.lang.kotlin.resolve

import com.intellij.psi.PsiElement
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtConstantExpression
import org.jetbrains.kotlin.psi.KtDoWhileExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtForExpression
import org.jetbrains.kotlin.psi.KtFunctionLiteral
import org.jetbrains.kotlin.psi.KtIfExpression
import org.jetbrains.kotlin.psi.KtIsExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtParenthesizedExpression
import org.jetbrains.kotlin.psi.KtPostfixExpression
import org.jetbrains.kotlin.psi.KtPrefixExpression
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtWhenExpression
import org.jetbrains.kotlin.psi.KtWhileExpression
import org.jetbrains.kotlin.psi.psiUtil.getStrictParentOfType

/**
 * CFG-based NULLABILITY narrowing for local `var`s — the reassignment-aware complement to the position-based
 * [smartCastNonNull] (which is sound only for immutable values). A `var` is smart-castable only when
 * *effectively immutable* (Kotlin spec): its narrowing must not be invalidated by a reassignment or a captured
 * write between the guard and the use. A position walk can't see reassignments, so this does a small
 * data-flow pass over the control-flow structure: it narrows a `var` at a null guard, RESETS it at each
 * reassignment, and (conservatively) DROPS any `var` written inside a loop or a closure from the analysis
 * entirely. The result — the set of `var` reads proven non-null — feeds the same null-family checks.
 *
 * Soundness: a `var` read is reported non-null only via a real narrowing that a later reassignment hasn't
 * undone; every construct the pass can't model leaves the value UNKNOWN (not non-null), so it under-reports and
 * never produces a false "non-null". Values written in a loop/closure are excluded, so a back-edge or captured
 * write can never invalidate a conclusion.
 */
internal enum class VarNul { NOT_NULL, NULL, UNKNOWN }

/** Whether [ref] is a local `var` that flow-analysis proves non-null at its position. False for anything that
 *  isn't an effectively-immutable tracked local var (immutable values are handled by the position-based path). */
internal fun KotlinResolver.varSmartCastNonNull(ref: KtNameReferenceExpression): Boolean {
    val root =
        ref.getStrictParentOfType<org.jetbrains.kotlin.psi.KtDeclarationWithBody>()?.bodyBlockExpression
            ?: return false
    val nonNull = caches.varNonNull.getOrPut(root) { KotlinVarNullFlow(this).analyze(root) }
    return ref in nonNull
}

internal class KotlinVarNullFlow(private val resolver: KotlinResolver) {
    private val flow = KotlinControlFlow(resolver)
    private val notNull = HashSet<PsiElement>()
    private lateinit var tracked: Set<KtProperty>

    /** The `var` references in [body] proven non-null by the flow. */
    fun analyze(body: KtBlockExpression): Set<PsiElement> {
        val all = HashSet<KtProperty>()
        collectLocalVars(body, all)
        if (all.isEmpty()) return emptySet()
        val excluded = HashSet<KtProperty>()
        markLoopOrClosureAssigned(
            body,
            all,
            excluded
        ) // a back-edge / captured write → not effectively immutable
        tracked = all - excluded
        if (tracked.isEmpty()) return emptySet()
        flowNull(body, emptyMap())
        return notNull
    }

    /** Thread the per-var nullability state through [element] in execution order, recording non-null reads. */
    private fun flowNull(
        element: PsiElement?,
        state: Map<KtProperty, VarNul>
    ): Map<KtProperty, VarNul> {
        when (val e = if (element is KtExpression) unwrap(element) else element) {
            null -> return state
            // Don't descend into closures / nested type bodies: a `var` is NOT smart-cast across a lambda boundary
            // in Kotlin (so a read inside is never recorded non-null), and tracked vars are never written in a
            // closure (excluded above), so the enclosing state is unchanged.
            is KtFunctionLiteral, is KtNamedFunction, is KtClassOrObject -> return state
            is KtBlockExpression -> {
                var s = state
                for (st in e.statements) s = flowNull(st, s)
                return s
            }

            is KtProperty -> {
                val s = flowNull(e.initializer, state)
                return if (e in tracked) s + (e to nulOf(e.initializer, s)) else s
            }

            is KtBinaryExpression -> return binary(e, state)
            is KtPostfixExpression -> {
                val s = flowNull(e.baseExpression, state)
                // `x!!` proves x non-null afterwards.
                if (e.operationToken == KtTokens.EXCLEXCL) {
                    boundVar(e.baseExpression)?.let { return s + (it to VarNul.NOT_NULL) }
                }
                return s
            }

            is KtIfExpression -> {
                val afterCond = flowNull(e.condition, state)
                val thenS = flowNull(e.then, refine(afterCond, e.condition, true))
                val elseIn = refine(afterCond, e.condition, false)
                val elseS = if (e.`else` != null) flowNull(e.`else`, elseIn) else elseIn
                // Only paths that can REACH the code after the `if` contribute (a `return`/`throw` arm drops out) —
                // this yields the early-exit narrowing `if (x == null) return; x.use`.
                val thenLive = e.then != null && flow.liveness(e.then) != Liveness.DEAD
                val elseLive =
                    if (e.`else` != null) flow.liveness(e.`else`) != Liveness.DEAD else true
                return when {
                    thenLive && elseLive -> merge(thenS, elseS)
                    thenLive -> thenS
                    elseLive -> elseS
                    else -> afterCond
                }
            }

            is KtWhenExpression -> {
                val afterSubject = flowNull(e.subjectExpression, state)
                e.entries.forEach {
                    flowNull(
                        it.expression,
                        afterSubject
                    )
                } // branch bodies see the entry state
                return afterSubject // conservative: don't merge branch outs
            }

            is KtWhileExpression -> {
                val afterCond = flowNull(e.condition, state)
                flowNull(
                    e.body,
                    refine(afterCond, e.condition, true)
                ) // body reads narrowed by the loop condition
                return refine(
                    afterCond,
                    e.condition,
                    false
                )           // after the loop the condition is false
            }

            is KtDoWhileExpression -> {
                val afterBody = flowNull(e.body, state)
                return flowNull(e.condition, afterBody)
            }

            is KtForExpression -> {
                val afterRange = flowNull(e.loopRange, state)
                flowNull(e.body, afterRange)
                return afterRange
            }

            is KtCallExpression -> {
                // Record reads in the receiver + arguments first, then apply a precondition STATEMENT's effect:
                // `requireNotNull(v)`/`checkNotNull(v)` proves v non-null; `require(cond)`/`check(cond)` narrows
                // as if cond held (it throws otherwise) — so `requireNotNull(v); v.use` / `require(v != null); …`
                // don't wrongly flag the following read.
                var s = state
                var c = e.firstChild
                while (c != null) { s = flowNull(c, s); c = c.nextSibling }
                val callee = (e.calleeExpression as? KtNameReferenceExpression)?.getReferencedName()
                val arg = e.valueArguments.singleOrNull()?.getArgumentExpression()
                when (callee) {
                    "requireNotNull", "checkNotNull" -> boundVar(arg)?.let { s = s + (it to VarNul.NOT_NULL) }
                    "require", "check" -> if (arg != null) s = refine(s, arg, true)
                }
                return s
            }

            is KtNameReferenceExpression -> {
                val v = boundVar(e)
                if (v != null && state[v] == VarNul.NOT_NULL) notNull.add(e)
                return state
            }

            else -> {
                var s = state
                var c = e.firstChild
                while (c != null) {
                    s = flowNull(c, s); c = c.nextSibling
                }
                return s
            }
        }
    }

    private fun binary(
        e: KtBinaryExpression,
        state: Map<KtProperty, VarNul>
    ): Map<KtProperty, VarNul> {
        when (e.operationToken) {
            KtTokens.EQ -> {
                val s = flowNull(e.right, state)
                val target = boundVar(e.left)
                return if (target != null) s + (target to nulOf(e.right, s)) else flowNull(
                    e.left,
                    s
                )
            }

            in COMPOUND_ASSIGN -> {
                val s = flowNull(e.right, state)
                val target = boundVar(e.left)
                return if (target != null) s + (target to VarNul.UNKNOWN) else flowNull(e.left, s)
            }

            KtTokens.ANDAND -> {
                val l = flowNull(e.left, state)
                flowNull(e.right, refine(l, e.left, true)) // RHS sees the LHS-true narrowing
                return l
            }

            KtTokens.OROR -> {
                val l = flowNull(e.left, state)
                flowNull(e.right, refine(l, e.left, false))
                return l
            }

            KtTokens.ELVIS -> {
                val l = flowNull(e.left, state)
                val target = boundVar(e.left)
                // `x ?: <jump>` proves x non-null afterwards; otherwise the elvis result isn't a narrowing of x.
                if (target != null && flow.liveness(e.right) == Liveness.DEAD) return l + (target to VarNul.NOT_NULL)
                flowNull(e.right, if (target != null) l + (target to VarNul.NULL) else l)
                return l
            }

            else -> return flowNull(e.right, flowNull(e.left, state))
        }
    }

    /** Refine [state] with the nullability facts a condition imposes when it evaluates to [whenTrue]. */
    private fun refine(
        state: Map<KtProperty, VarNul>,
        cond: KtExpression?,
        whenTrue: Boolean
    ): Map<KtProperty, VarNul> {
        val c = unwrap(cond) ?: return state
        return when {
            c is KtBinaryExpression && c.operationToken == KtTokens.EXCLEQ ->
                nullCmpVar(c)?.let {
                    set(
                        state,
                        it,
                        if (whenTrue) VarNul.NOT_NULL else VarNul.NULL
                    )
                } ?: state

            c is KtBinaryExpression && c.operationToken == KtTokens.EQEQ ->
                nullCmpVar(c)?.let {
                    set(
                        state,
                        it,
                        if (whenTrue) VarNul.NULL else VarNul.NOT_NULL
                    )
                } ?: state

            c is KtBinaryExpression && c.operationToken == KtTokens.ANDAND ->
                if (whenTrue) refine(refine(state, c.left, true), c.right, true) else state

            c is KtBinaryExpression && c.operationToken == KtTokens.OROR ->
                if (!whenTrue) refine(refine(state, c.left, false), c.right, false) else state

            c is KtIsExpression -> {
                val v = boundVar(c.leftHandSide)
                if (v != null && whenTrue != c.isNegated && isNonNullIs(c.typeReference?.text)) set(
                    state,
                    v,
                    VarNul.NOT_NULL
                ) else state
            }

            c is KtPrefixExpression && c.operationToken == KtTokens.EXCL -> refine(
                state,
                c.baseExpression,
                !whenTrue
            )

            c is KtCallExpression -> {
                // `requireNotNull(x)` / `checkNotNull(x)` (used as a condition is unusual, but harmless).
                val callee = (c.calleeExpression as? KtNameReferenceExpression)?.getReferencedName()
                if ((callee == "requireNotNull" || callee == "checkNotNull") && whenTrue) {
                    boundVar(
                        c.valueArguments.singleOrNull()?.getArgumentExpression()
                    )?.let { return set(state, it, VarNul.NOT_NULL) }
                }
                state
            }

            else -> state
        }
    }

    /** The tracked var a `x == null` / `x != null` comparison refers to (either operand order), else null. */
    private fun nullCmpVar(e: KtBinaryExpression): KtProperty? {
        val l = e.left?.let { unwrap(it) }
        val r = e.right?.let { unwrap(it) }
        if (isNullLit(r)) return boundVar(l)
        if (isNullLit(l)) return boundVar(r)
        return null
    }

    private fun set(
        state: Map<KtProperty, VarNul>,
        v: KtProperty,
        nul: VarNul
    ): Map<KtProperty, VarNul> =
        if (v in tracked) state + (v to nul) else state

    /** The nullability of a value expression: a null literal → NULL, a non-null literal / `!!` / a tracked var
     *  known non-null → NOT_NULL, otherwise UNKNOWN. */
    private fun nulOf(expr: KtExpression?, state: Map<KtProperty, VarNul>): VarNul =
        when (val e = unwrap(expr)) {
            null -> VarNul.UNKNOWN
            is KtConstantExpression -> if (e.text == "null") VarNul.NULL else VarNul.NOT_NULL
            is org.jetbrains.kotlin.psi.KtStringTemplateExpression -> VarNul.NOT_NULL
            is KtPostfixExpression -> if (e.operationToken == KtTokens.EXCLEXCL) VarNul.NOT_NULL else VarNul.UNKNOWN
            is KtNameReferenceExpression -> boundVar(e)?.let { state[it] } ?: VarNul.UNKNOWN
            else -> VarNul.UNKNOWN
        }

    private fun merge(
        a: Map<KtProperty, VarNul>,
        b: Map<KtProperty, VarNul>
    ): Map<KtProperty, VarNul> {
        val out = HashMap<KtProperty, VarNul>()
        for (k in a.keys.intersect(b.keys)) if (a[k] == b[k]) out[k] =
            a[k]!! // equal on both paths → keep; else UNKNOWN (absent)
        return out
    }

    private fun collectLocalVars(root: PsiElement, out: MutableSet<KtProperty>) {
        fun rec(p: PsiElement) {
            if (p is KtProperty && p.parent is KtBlockExpression && p.isVar && !p.hasDelegate() && p.name != null) out.add(
                p
            )
            var c = p.firstChild
            while (c != null) {
                rec(c); c = c.nextSibling
            }
        }
        rec(root)
    }

    /** Exclude a var written inside a loop body or a closure — a back-edge or captured write could invalidate a
     *  narrowing, so it is not effectively immutable. */
    private fun markLoopOrClosureAssigned(
        root: PsiElement,
        all: Set<KtProperty>,
        excluded: MutableSet<KtProperty>
    ) {
        fun rec(p: PsiElement, inside: Boolean) {
            val here =
                inside || p is org.jetbrains.kotlin.psi.KtLoopExpression || p is KtFunctionLiteral || p is KtNamedFunction
            if (here && p is KtBinaryExpression && (p.operationToken == KtTokens.EQ || p.operationToken in COMPOUND_ASSIGN)) {
                boundVarIn(p.left, all)?.let { excluded.add(it) }
            }
            if (here && p is KtPostfixExpression && p.operationToken in INCDEC) boundVarIn(
                p.baseExpression,
                all
            )?.let { excluded.add(it) }
            var c = p.firstChild
            while (c != null) {
                rec(c, if (p === root) inside else here); c = c.nextSibling
            }
        }
        rec(root, false)
    }

    private fun boundVar(ref: KtExpression?): KtProperty? =
        (unwrap(ref) as? KtNameReferenceExpression)?.let { boundVarIn(it, tracked) }

    private fun boundVarIn(ref: KtExpression?, set: Set<KtProperty>): KtProperty? {
        val r = unwrap(ref) as? KtNameReferenceExpression ?: return null
        val name = r.getReferencedName()
        val offset = r.textRange.startOffset
        var node: PsiElement? = r.parent
        while (node != null) {
            when (node) {
                is KtBlockExpression -> node.statements.firstOrNull {
                    it is KtProperty && it.name == name && it.textRange.endOffset <= offset
                }?.let { return (it as KtProperty).takeIf { p -> p in set } ?: return null }

                is KtClassOrObject -> return null
            }
            node = node.parent
        }
        return null
    }

    private fun isNullLit(e: PsiElement?): Boolean = e is KtConstantExpression && e.text == "null"
    private fun isNonNullIs(typeText: String?): Boolean =
        typeText != null && !typeText.trim().endsWith("?")

    private fun unwrap(e: KtExpression?): KtExpression? = when (e) {
        is KtParenthesizedExpression -> unwrap(e.expression)
        is org.jetbrains.kotlin.psi.KtAnnotatedExpression -> unwrap(e.baseExpression)
        is org.jetbrains.kotlin.psi.KtLabeledExpression -> unwrap(e.baseExpression)
        else -> e
    }

    private companion object {
        val COMPOUND_ASSIGN = setOf(
            KtTokens.PLUSEQ,
            KtTokens.MINUSEQ,
            KtTokens.MULTEQ,
            KtTokens.DIVEQ,
            KtTokens.PERCEQ
        )
        val INCDEC = setOf(KtTokens.PLUSPLUS, KtTokens.MINUSMINUS)
    }
}
