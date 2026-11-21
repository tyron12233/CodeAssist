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
import dev.ide.kotlin.syntax.psi.KtExpressionWithLabel
import dev.ide.kotlin.syntax.psi.KtForExpression
import dev.ide.kotlin.syntax.psi.KtFunctionLiteral
import dev.ide.kotlin.syntax.psi.KtIfExpression
import dev.ide.kotlin.syntax.psi.KtLabeledExpression
import dev.ide.kotlin.syntax.psi.KtLambdaExpression
import dev.ide.kotlin.syntax.psi.KtLoopExpression
import dev.ide.kotlin.syntax.psi.KtNameReferenceExpression
import dev.ide.kotlin.syntax.psi.KtNamedFunction
import dev.ide.kotlin.syntax.psi.KtParenthesizedExpression
import dev.ide.kotlin.syntax.psi.KtProperty
import dev.ide.kotlin.syntax.psi.KtQualifiedExpression
import dev.ide.kotlin.syntax.psi.KtReturnExpression
import dev.ide.kotlin.syntax.psi.KtThrowExpression
import dev.ide.kotlin.syntax.psi.KtTokens
import dev.ide.kotlin.syntax.psi.KtTryExpression
import dev.ide.kotlin.syntax.psi.KtWhenExpression
import dev.ide.kotlin.syntax.psi.KtWhileExpression

/**
 * Assignments to a local `val` declared without a value (`val x: Int`) that may not be its first: Kotlin's
 * VAL_REASSIGNMENT for deferred initialization. A forward MAY-analysis over the body: the state is the set of
 * such `val`s that some path to this point has assigned. A second assignment on a path, one in a loop that can
 * come round again (`for (…) { i = j }`), one in a `catch` or `finally` after the `try` may already have
 * assigned it, are all reported.
 *
 * Paths that leave (a `return`, a `throw`, a `break` or `continue`) carry nothing forward except to where they
 * jump. A lambda, a local function or a class is not entered: what it does to a captured `val` is a different
 * diagnostic, and an assignment inside it is not counted afterwards.
 */
internal class KotlinValReassignFlow(resolver: KotlinResolver) {
    private val flow = KotlinControlFlow(resolver)
    private val tracked = HashSet<KtProperty>()
    private val out = LinkedHashSet<KtNameReferenceExpression>()
    private val breaks = HashMap<KtElement, Set<KtProperty>?>()
    private val continues = HashMap<KtElement, Set<KtProperty>?>()
    private var quiet = 0

    fun reassignments(body: KtBlockExpression): List<KtNameReferenceExpression> {
        collect(body)
        if (tracked.isEmpty()) return emptyList()
        walk(body, emptySet())
        return out.toList()
    }

    private fun collect(root: KtElement) {
        if (root is KtProperty && root.parent is KtBlockExpression && !root.isVar && !root.hasInitializer() &&
            !root.hasDelegate() && root.name != null) tracked += root
        if (root is KtFunctionLiteral || root is KtNamedFunction || root is KtClassOrObject) return
        var c = root.firstChild
        while (c != null) { collect(c); c = c.nextSibling }
    }

    private fun union(a: Set<KtProperty>?, b: Set<KtProperty>?): Set<KtProperty>? =
        if (a == null) b else if (b == null) a else a + b

    private fun walkChildren(e: KtElement, s: Set<KtProperty>?): Set<KtProperty>? {
        var st = s
        var c = e.firstChild
        while (c != null) {
            st = if (c is KtExpression) walk(c, st) else if (c is KtClassOrObject) st else walkChildren(c, st)
            c = c.nextSibling
        }
        return st
    }

    private fun walk(e: KtExpression?, s: Set<KtProperty>?): Set<KtProperty>? {
        if (e == null || s == null) return s
        return when (e) {
            is KtParenthesizedExpression -> walk(e.expression, s)
            is KtAnnotatedExpression -> walk(e.baseExpression, s)
            is KtLabeledExpression -> walk(e.baseExpression, s)
            is KtLambdaExpression, is KtNamedFunction, is KtClassOrObject -> s
            is KtBlockExpression -> {
                var st: Set<KtProperty>? = s
                for (x in e.statements) st = walk(x, st)
                st
            }
            // A `val` declared in a loop body is a new variable on every iteration.
            is KtProperty -> walk(e.initializer, s)?.let { if (e in tracked) it - e else it }
            is KtBinaryExpression -> when (e.operationToken) {
                KtTokens.EQ -> {
                    val st = walk(e.right, s) ?: return null
                    val v = target(e.left)
                    if (v == null) walk(e.left, st)
                    else {
                        if (v in st && quiet == 0) out += unwrap(e.left) as KtNameReferenceExpression
                        st + v
                    }
                }
                KtTokens.ANDAND, KtTokens.OROR, KtTokens.ELVIS -> {
                    val l = walk(e.left, s)
                    union(l, walk(e.right, l))
                }
                else -> walk(e.right, walk(e.left, s))
            }
            is KtIfExpression -> {
                val c = walk(e.condition, s)
                union(walk(e.then, c), if (e.`else` != null) walk(e.`else`, c) else c)
            }
            is KtWhenExpression -> {
                var st = walk(e.subjectVariable ?: e.subjectExpression, s)
                var result: Set<KtProperty>? = null
                var hasElse = false
                for (entry in e.entries) {
                    if (entry.isElse) hasElse = true
                    for (c in entry.conditions) st = walkChildren(c, st)
                    result = union(result, walk(entry.expression, st))
                }
                if (!hasElse) result = union(result, st)
                result
            }
            is KtReturnExpression -> { walk(e.returnedExpression, s); null }
            is KtThrowExpression -> { walk(e.thrownExpression, s); null }
            is KtBreakExpression -> { jumpTarget(e)?.let { breaks[it] = union(breaks[it], s) }; null }
            is KtContinueExpression -> { jumpTarget(e)?.let { continues[it] = union(continues[it], s) }; null }
            is KtWhileExpression -> loop(e, s) { entry ->
                val c = walk(e.condition, entry)
                val body = walk(e.body, c)
                Pair(union(body, continues.remove(e)), if (isTrue(e.condition)) null else c)
            }
            is KtDoWhileExpression -> loop(e, s) { entry ->
                val body = walk(e.body, entry)
                val c = walk(e.condition, union(body, continues.remove(e)))
                Pair(c, if (isTrue(e.condition)) null else c)
            }
            is KtForExpression -> {
                val r = walk(e.loopRange, s)
                loop(e, r) { entry ->
                    val body = walk(e.body, entry)
                    Pair(union(body, continues.remove(e)), entry)
                }
            }
            is KtTryExpression -> {
                val tryOut = walk(e.tryBlock, s)
                // A catch or finally may run after any part of the try block did.
                val anywhere = union(s + assignedIn(e.tryBlock), tryOut)
                var normal = tryOut
                for (cc in e.catchClauses) normal = union(normal, walk(cc.catchBody, anywhere))
                val fin = e.finallyBlock?.finalExpression ?: return normal
                val all = union(anywhere, normal)
                walk(fin, all)
                if (normal == null) null else { quiet++; try { walk(fin, normal) } finally { quiet-- } }
            }
            // A call that never returns (`error()`, `TODO()`, a `Nothing` function) leaves what follows unreached.
            is KtCallExpression, is KtDotQualifiedExpression ->
                walkChildren(e, s)?.takeIf { flow.liveness(e) != Liveness.DEAD }
            else -> walkChildren(e, s)
        }
    }

    /**
     * A loop: its body is walked once quietly from [entry] to learn what comes round the back edge, then again,
     * reporting, from [entry] joined with that. [step] walks one iteration and gives back the state at the back
     * edge and the state on leaving through the condition.
     */
    private fun loop(
        e: KtLoopExpression, entry: Set<KtProperty>?,
        step: (Set<KtProperty>?) -> Pair<Set<KtProperty>?, Set<KtProperty>?>,
    ): Set<KtProperty>? {
        if (entry == null) return null
        quiet++
        val (back, _) = try { step(entry) } finally { quiet-- }
        breaks.remove(e); continues.remove(e)
        val (_, exit) = step(union(entry, back))
        return union(exit, breaks.remove(e))
    }

    private fun assignedIn(region: KtElement): Set<KtProperty> {
        val res = HashSet<KtProperty>()
        fun rec(p: KtElement) {
            if (p is KtBinaryExpression && p.operationToken == KtTokens.EQ) target(p.left)?.let { res += it }
            if (p is KtLambdaExpression || p is KtNamedFunction || p is KtClassOrObject) return
            var c = p.firstChild
            while (c != null) { rec(c); c = c.nextSibling }
        }
        rec(region)
        return res
    }

    /** The tracked `val` an assignment's left side names. */
    private fun target(lhs: KtExpression?): KtProperty? {
        val ref = unwrap(lhs) as? KtNameReferenceExpression ?: return null
        val name = ref.getReferencedName()
        val offset = ref.textRange.startOffset
        var node: KtElement? = ref.parent
        while (node != null) {
            when (node) {
                is KtBlockExpression -> node.statements.firstOrNull {
                    it is KtProperty && it.name == name && it.textRange.endOffset <= offset
                }?.let { return (it as KtProperty).takeIf { p -> p in tracked } }
                is KtFunctionLiteral, is KtNamedFunction, is KtClassOrObject -> return null
                is KtQualifiedExpression -> if (node.selectorExpression === ref) return null
            }
            node = node.parent
        }
        return null
    }

    private fun jumpTarget(e: KtExpressionWithLabel): KtElement? {
        val label = e.getLabelName()
        var node: KtElement? = e.parent
        while (node != null && node !is KtFunctionLiteral && node !is KtNamedFunction && node !is KtClassOrObject) {
            if (node is KtLoopExpression && (label == null || (node.parent as? KtLabeledExpression)?.getLabelName() == label)) return node
            node = node.parent
        }
        return null
    }

    private fun isTrue(e: KtExpression?): Boolean = unwrap(e)?.text == "true"

    private fun unwrap(e: KtExpression?): KtExpression? = when (e) {
        is KtParenthesizedExpression -> unwrap(e.expression)
        is KtAnnotatedExpression -> unwrap(e.baseExpression)
        is KtLabeledExpression -> unwrap(e.baseExpression)
        else -> e
    }
}
