package dev.ide.lang.kotlin.resolve

import dev.ide.kotlin.syntax.psi.KtAnnotatedExpression
import dev.ide.kotlin.syntax.psi.KtArrayAccessExpression
import dev.ide.kotlin.syntax.psi.KtBinaryExpression
import dev.ide.kotlin.syntax.psi.KtBinaryExpressionWithTypeRHS
import dev.ide.kotlin.syntax.psi.KtBlockExpression
import dev.ide.kotlin.syntax.psi.KtBreakExpression
import dev.ide.kotlin.syntax.psi.KtCallExpression
import dev.ide.kotlin.syntax.psi.KtCallableReferenceExpression
import dev.ide.kotlin.syntax.psi.KtCatchClause
import dev.ide.kotlin.syntax.psi.KtClass
import dev.ide.kotlin.syntax.psi.KtClassOrObject
import dev.ide.kotlin.syntax.psi.KtConstantExpression
import dev.ide.kotlin.syntax.psi.KtContinueExpression
import dev.ide.kotlin.syntax.psi.KtDeclarationWithBody
import dev.ide.kotlin.syntax.psi.KtDestructuringDeclaration
import dev.ide.kotlin.syntax.psi.KtDoWhileExpression
import dev.ide.kotlin.syntax.psi.KtDotQualifiedExpression
import dev.ide.kotlin.syntax.psi.KtElement
import dev.ide.kotlin.syntax.psi.KtExpression
import dev.ide.kotlin.syntax.psi.KtExpressionWithLabel
import dev.ide.kotlin.syntax.psi.KtFile
import dev.ide.kotlin.syntax.psi.KtForExpression
import dev.ide.kotlin.syntax.psi.KtFunction
import dev.ide.kotlin.syntax.psi.KtFunctionLiteral
import dev.ide.kotlin.syntax.psi.KtIfExpression
import dev.ide.kotlin.syntax.psi.KtIsExpression
import dev.ide.kotlin.syntax.psi.KtLabeledExpression
import dev.ide.kotlin.syntax.psi.KtLambdaArgument
import dev.ide.kotlin.syntax.psi.KtLambdaExpression
import dev.ide.kotlin.syntax.psi.KtLoopExpression
import dev.ide.kotlin.syntax.psi.KtNameReferenceExpression
import dev.ide.kotlin.syntax.psi.KtNamedFunction
import dev.ide.kotlin.syntax.psi.KtNullableType
import dev.ide.kotlin.syntax.psi.KtObjectLiteralExpression
import dev.ide.kotlin.syntax.psi.KtParameter
import dev.ide.kotlin.syntax.psi.KtParenthesizedExpression
import dev.ide.kotlin.syntax.psi.KtPostfixExpression
import dev.ide.kotlin.syntax.psi.KtPrefixExpression
import dev.ide.kotlin.syntax.psi.KtProperty
import dev.ide.kotlin.syntax.psi.KtQualifiedExpression
import dev.ide.kotlin.syntax.psi.KtReturnExpression
import dev.ide.kotlin.syntax.psi.KtSafeQualifiedExpression
import dev.ide.kotlin.syntax.psi.KtStringTemplateExpression
import dev.ide.kotlin.syntax.psi.KtThrowExpression
import dev.ide.kotlin.syntax.psi.KtTokens
import dev.ide.kotlin.syntax.psi.KtTryExpression
import dev.ide.kotlin.syntax.psi.KtUnaryExpression
import dev.ide.kotlin.syntax.psi.KtValueArgument
import dev.ide.kotlin.syntax.psi.KtValueArgumentList
import dev.ide.kotlin.syntax.psi.KtWhenConditionIsPattern
import dev.ide.kotlin.syntax.psi.KtWhenConditionWithExpression
import dev.ide.kotlin.syntax.psi.KtWhenExpression
import dev.ide.kotlin.syntax.psi.KtWhileExpression

/**
 * Data-flow NULLABILITY narrowing for local variables, modeled on the K2 front end's data-flow analysis. It
 * complements the position-based [smartCastNonNull] walk (which reasons only about values that cannot change)
 * with a forward pass over the whole function body that also follows reassignment.
 *
 * What is tracked: every local `val`/`var` and every parameter of the function, of its lambdas, `for` loops
 * and `catch` clauses. The state at each point maps each to what is known of its nullness, plus the aliases
 * Kotlin keeps (`var b = a` makes `b` an alias of `a` until either is reassigned: a check on one speaks for
 * the other) and the variables that are no longer stable at all.
 *
 * The rules follow K2:
 *  - a loop does not iterate to a fixpoint. On entry to it every variable the loop assigns forgets what was
 *    known of it (K2's preliminary loop visitor), so the body, the condition and the code after see only facts
 *    no iteration can undo. The code after a loop joins the condition-false exit with every `break` to it;
 *  - a lambda passed to a function that calls it in place (a stdlib scope function, a function declaring
 *    `callsInPlace`, any lambda parameter of an `inline` function) is flowed where the call is. One called
 *    exactly once replaces the state; one that may not run is joined with it; one that may run repeatedly
 *    forgets, at its entry, what it assigns, as a loop does;
 *  - any other lambda, a local function or an object runs at an unknown time. A variable it assigns is
 *    unstable from the point it is created onward, and inside it an outer variable keeps its facts only when
 *    nothing assigns it after the lambda exists.
 *
 * The result is the set of reads proven non-null on every path that reaches them. A read reached twice (a
 * `finally` block, which runs on both the normal and the exceptional path) is proven only if both prove it.
 */
internal enum class VarNul {
    /** Proven by a check, a literal, `!!` or a cast: no doubt at all. */
    NOT_NULL,
    /** Non-null by the inferred type of what was assigned, which may be a Java platform type: enough to allow a
     *  call, not to call a `!!` or `?.` on it redundant. */
    NN_INFERRED,
    NULL,
    UNKNOWN;

    val nonNull: Boolean get() = this == NOT_NULL || this == NN_INFERRED
}

/** The weaker of two facts that both hold somewhere, or null when they disagree. */
private fun joinNul(a: VarNul?, b: VarNul?): VarNul? = when {
    a == null || b == null -> null
    a == b -> a
    a.nonNull && b.nonNull -> VarNul.NN_INFERRED
    else -> null
}

/** Whether [ref] reads a local variable or parameter that the data-flow pass proves non-null at its position. */
internal fun KotlinResolver.varSmartCastNonNull(ref: KtNameReferenceExpression, strict: Boolean = false): Boolean {
    val root = flowRoot(ref) ?: return false
    if (!varFlowInProgress.add(root)) return false
    try {
        val nonNull = caches.varNonNull.getOrPut(root) { KotlinVarNullFlow(this).analyze(root as KtDeclarationWithBody) }
        val nul = nonNull[ref] ?: return false
        return if (strict) nul == VarNul.NOT_NULL else nul.nonNull
    } finally {
        varFlowInProgress.remove(root)
    }
}

/** Roots whose analysis is running on this thread's resolver, so that a re-entrant ask (inference asking the
 *  pass a question the pass is computing) answers "not proven" instead of recursing. */
private val KotlinResolver.varFlowInProgress: MutableSet<KtElement>
    get() = caches.varFlowInProgress

/**
 * The declaration whose body the pass analyzes for [ref]: the outermost function, accessor or constructor
 * around it within its class or file, so that a read inside a lambda or a local function is judged in the
 * context the lambda is created in. A read in a class-level initializer has none.
 */
private fun flowRoot(ref: KtElement): KtElement? {
    var best: KtElement? = null
    var node: KtElement? = ref.parent
    while (node != null && node !is KtClassOrObject && node !is KtFile) {
        if (node is KtDeclarationWithBody && node.bodyExpression != null) best = node
        node = node.parent
    }
    return best
}

/** What is known at one point of the flow. A null state (in [KotlinVarNullFlow]) is an unreachable point. */
private class FlowState(
    val facts: Map<KtElement, VarNul>,
    /** `b -> a`: `b` holds the same value as `a` (the root of the alias class). */
    val alias: Map<KtElement, KtElement>,
    /** Variables some concurrently running code may write: nothing is known of them. */
    val unstable: Set<KtElement>,
    /** Which value each variable holds: a new number at every assignment (K2's assignment index). Two points
     *  with the same number see the same value. */
    val ver: Map<KtElement, Int>,
) {
    companion object { val EMPTY = FlowState(emptyMap(), emptyMap(), emptySet(), emptyMap()) }
}

internal enum class InPlace { EXACTLY_ONCE, AT_LEAST_ONCE, AT_MOST_ONCE, UNKNOWN }

internal class KotlinVarNullFlow(private val resolver: KotlinResolver) {
    private val flow = KotlinControlFlow(resolver)

    /** Per read: the weakest non-null fact every visit so far had (absent: not yet visited). */
    private val proven = HashMap<KtNameReferenceExpression, VarNul>()
    private val tracked = HashSet<KtElement>()
    private val breaks = HashMap<KtElement, FlowState?>()
    private val continues = HashMap<KtElement, FlowState?>()
    private val lambdaExits = HashMap<KtElement, FlowState?>()
    private val bindingCache = HashMap<KtNameReferenceExpression, KtElement?>()
    private val assignedCache = HashMap<Pair<KtElement, Boolean>, Set<KtElement>>()
    private val joinedVersions = HashMap<Triple<KtElement, Int, Int>, Int>()
    private var version = 0
    private val concurrentWritesCache = HashMap<KtElement, Set<KtElement>>()
    private val inPlaceCache = HashMap<KtLambdaExpression, InPlace?>()
    private lateinit var rootBody: KtExpression
    private var budget = 200_000

    /** The reads in [decl]'s body proven non-null by the flow. */
    fun analyze(decl: KtDeclarationWithBody): Map<KtElement, VarNul> {
        val body = decl.bodyExpression ?: return emptyMap()
        rootBody = body
        collectTracked(decl)
        if (tracked.isEmpty()) return emptyMap()
        try {
            flowExpr(body, FlowState.EMPTY)
        } catch (_: BudgetExceeded) {
            return emptyMap()
        }
        return proven.filterValues { it.nonNull }
    }

    private class BudgetExceeded : RuntimeException()

    // ---- tracked variables and their bindings ----

    private fun collectTracked(decl: KtDeclarationWithBody) {
        (decl as? KtFunction)?.valueParameters?.forEach { if (it.name != null) tracked += it }
        fun rec(p: KtElement) {
            when (p) {
                is KtProperty -> if (p.parent is KtBlockExpression && !p.hasDelegate() && p.name != null) tracked += p
                is KtFunctionLiteral -> p.valueParameters.forEach { if (it.name != null) tracked += it }
                is KtNamedFunction -> p.valueParameters.forEach { if (it.name != null) tracked += it }
                is KtForExpression -> p.loopParameter?.let { if (it.name != null) tracked += it }
                is KtCatchClause -> p.catchParameter?.let { if (it.name != null) tracked += it }
                is KtWhenExpression -> p.subjectVariable?.let { if (it.name != null && !it.hasDelegate()) tracked += it }
                is KtClassOrObject -> return
            }
            var c = p.firstChild
            while (c != null) { rec(c); c = c.nextSibling }
        }
        rec(rootBody)
    }

    private fun isMutable(v: KtElement): Boolean = v is KtProperty && v.isVar

    /** The tracked variable a bare name binds to, honouring shadowing; null when it binds to anything else. */
    private fun bound(e: KtExpression?): KtElement? {
        val ref = unwrap(e) as? KtNameReferenceExpression ?: return null
        return bindingCache.getOrPut(ref) { resolveBinding(ref) }
    }

    private fun resolveBinding(ref: KtNameReferenceExpression): KtElement? {
        val parent = ref.parent
        if (parent is KtQualifiedExpression && parent.selectorExpression === ref) return null
        if (parent is KtCallableReferenceExpression) return null
        val name = ref.getReferencedName()
        val offset = ref.textRange.startOffset
        var child: KtElement = ref
        var node: KtElement? = ref.parent
        while (node != null) {
            when (node) {
                is KtBlockExpression -> for (st in node.statements) {
                    if (st === child || st.textRange.startOffset > offset) break
                    if (st is KtProperty && st.name == name) return st.takeIf { it in tracked }
                    if (st is KtDestructuringDeclaration && st.entries.any { it.name == name }) return null
                    if (st is KtNamedFunction && st.name == name) return null
                    if (st is KtClassOrObject && st.name == name) return null
                }
                is KtFunctionLiteral -> {
                    if (node.valueParameters.any { it.destructuringDeclaration?.entries?.any { e -> e.name == name } == true }) return null
                    node.valueParameters.firstOrNull { it.name == name }?.let { return it.takeIf { p -> p in tracked } }
                }
                is KtFunction -> node.valueParameters.firstOrNull { it.name == name }?.let { return it.takeIf { p -> p in tracked } }
                is KtForExpression -> if (child === node.body) {
                    node.loopParameter?.let { lp ->
                        if (lp.name == name) return lp.takeIf { it in tracked }
                        if (lp.destructuringDeclaration?.entries?.any { it.name == name } == true) return null
                    }
                }
                is KtCatchClause -> node.catchParameter?.takeIf { it.name == name }?.let { return it.takeIf { p -> p in tracked } }
                is KtWhenExpression -> node.subjectVariable?.takeIf { it.name == name && child !== it }?.let { return it.takeIf { p -> p in tracked } }
                // A local class or object captures the variables around it, unless it declares the name itself.
                is KtClassOrObject -> if (declaresMember(node, name)) return null
                is KtFile -> return null
            }
            if (node === rootBody.parent) return null
            child = node
            node = node.parent
        }
        return null
    }

    private fun declaresMember(cls: KtClassOrObject, name: String): Boolean =
        cls.declarations.any { it is KtProperty && it.name == name } ||
            cls.primaryConstructor?.valueParameters?.any { it.name == name } == true

    // ---- state operations ----

    private fun known(s: FlowState, v: KtElement): VarNul? =
        if (v in s.unstable) null else s.facts[v]

    /** Records [nul] for [v] and every variable aliased with it. */
    private fun refineVar(s: FlowState?, v: KtElement?, nul: VarNul): FlowState? {
        if (s == null || v == null || v in s.unstable) return s
        val root = s.alias[v] ?: v
        val facts = HashMap(s.facts)
        facts[root] = nul
        for ((b, r) in s.alias) if (r == root && b !in s.unstable) facts[b] = nul
        return FlowState(facts, s.alias, s.unstable, s.ver)
    }

    /** `v = value`: [v] leaves its alias class, whatever aliased [v] keeps its facts but stops following it, and
     *  [v] takes [nul] (and becomes an alias of [source] when the value is another variable). */
    private fun assign(s: FlowState?, v: KtElement, nul: VarNul, source: KtElement?): FlowState? {
        if (s == null) return null
        val alias = HashMap(s.alias)
        alias.remove(v)
        alias.entries.removeAll { it.value == v }
        val facts = HashMap(s.facts)
        val ver = HashMap(s.ver)
        ver[v] = ++version
        if (v in s.unstable) {
            facts.remove(v)
            return FlowState(facts, alias, s.unstable, ver)
        }
        facts[v] = nul
        if (source != null && source != v && source !in s.unstable) {
            val root = alias[source] ?: source
            if (root != v) {
                alias[v] = root
                s.facts[source]?.let { facts[v] = it } ?: facts.remove(v)
                if (nul.nonNull) facts[v] = joinNul(facts[v], nul) ?: nul
            }
        }
        if (facts[v] == VarNul.UNKNOWN) facts.remove(v)
        return FlowState(facts, alias, s.unstable, ver)
    }

    /** Forgets every fact about [vars], and their aliases; [unstable] become unstable for good. */
    private fun forget(s: FlowState?, vars: Set<KtElement>, unstable: Set<KtElement> = emptySet()): FlowState? {
        if (s == null || (vars.isEmpty() && unstable.isEmpty())) return s
        val all = vars + unstable
        val facts = HashMap(s.facts)
        all.forEach { facts.remove(it) }
        val alias = HashMap(s.alias)
        alias.entries.removeAll { it.key in all || it.value in all }
        val ver = HashMap(s.ver)
        all.forEach { ver[it] = ++version }
        return FlowState(facts, alias, if (unstable.isEmpty()) s.unstable else s.unstable + unstable, ver)
    }

    private fun merge(a: FlowState?, b: FlowState?): FlowState? {
        if (a == null) return b
        if (b == null) return a
        if (a === b) return a
        val facts = HashMap<KtElement, VarNul>()
        for ((k, v) in a.facts) joinNul(v, b.facts[k])?.let { facts[k] = it }
        val alias = HashMap<KtElement, KtElement>()
        for ((k, v) in a.alias) if (b.alias[k] == v) alias[k] = v
        val unstable = if (b.unstable.isEmpty()) a.unstable else if (a.unstable.isEmpty()) b.unstable else a.unstable + b.unstable
        val ver = HashMap<KtElement, Int>()
        for (k in a.ver.keys + b.ver.keys) {
            val x = a.ver[k]
            ver[k] = if (x != null && x == b.ver[k]) x else joinVersion(k, x, b.ver[k])
        }
        return FlowState(facts, alias, unstable, ver)
    }

    /** The version a join of two different values gets: the same for the same pair, so that joining equal
     *  inputs twice agrees. */
    private fun joinVersion(k: KtElement, a: Int?, b: Int?): Int =
        joinedVersions.getOrPut(Triple(k, minOf(a ?: -1, b ?: -1), maxOf(a ?: -1, b ?: -1))) { ++version }

    private fun mergeAll(states: List<FlowState?>): FlowState? = states.fold(null as FlowState?) { a, b -> merge(a, b) }

    /**
     * What a region may do to the state at any point inside it. For a loop or a lambda that may run repeatedly
     * ([plainOnly]) this is what K2 forgets on entry: the variables the region assigns with a plain `=`. An
     * operator assignment alone (`x += 1`, `x++`) is not counted there (KT-57563), which is what makes the
     * compiler accept `do { require(y is String); y += "" } while (c)`. A `try` block counts every assignment.
     */
    private fun havoc(s: FlowState?, region: KtElement, plainOnly: Boolean = true): FlowState? =
        forget(s, assignedIn(region, plainOnly), concurrentWritesIn(region))

    // ---- the walk ----

    private fun record(ref: KtNameReferenceExpression, s: FlowState?) {
        val v = bound(ref) ?: return
        val now = s?.let { known(it, v) }?.takeIf { it.nonNull } ?: VarNul.UNKNOWN
        val before = proven[ref]
        proven[ref] = if (before == null) now else joinNul(before, now) ?: VarNul.UNKNOWN
    }

    /** Walks a subtree that is never reached: no read in it is proven. */
    private fun markDead(e: KtElement?) {
        if (e == null) return
        if (e is KtNameReferenceExpression) { record(e, null); return }
        if (e is KtClassOrObject) return
        var c = e.firstChild
        while (c != null) { markDead(c); c = c.nextSibling }
    }

    private fun flowChildren(e: KtElement, s: FlowState?): FlowState? {
        var st = s
        var c = e.firstChild
        while (c != null) {
            st = flowElement(c, st)
            c = c.nextSibling
        }
        return st
    }

    private fun flowElement(e: KtElement, s: FlowState?): FlowState? =
        if (e is KtExpression) flowExpr(e, s) else when (e) {
            is KtCatchClause, is KtClassOrObject, is dev.ide.kotlin.syntax.psi.KtTypeReference,
            is dev.ide.kotlin.syntax.psi.KtValueArgumentName, is dev.ide.kotlin.syntax.psi.KtTypeArgumentList -> s
            else -> flowChildren(e, s)
        }

    private fun flowExpr(element: KtExpression?, s: FlowState?): FlowState? {
        if (element == null) return s
        if (--budget < 0) throw BudgetExceeded()
        if (s == null) { markDead(element); return null }
        return when (val e = element) {
            is KtParenthesizedExpression -> flowExpr(e.expression, s)
            is KtAnnotatedExpression -> flowExpr(e.baseExpression, s)
            is KtLabeledExpression -> flowExpr(e.baseExpression, s)
            is KtLambdaExpression -> concurrentLambda(e, s)
            is KtNamedFunction -> concurrentClosure(e, e.bodyExpression, s)
            is KtObjectLiteralExpression -> concurrentClosure(e, null, s)
            is KtClassOrObject -> concurrentClosure(e, null, s)
            is KtBlockExpression -> {
                var st: FlowState? = s
                for (x in e.statements) st = flowExpr(x, st)
                st
            }
            is KtProperty -> {
                val st = flowExpr(e.initializer, s)
                // A declaration with its own type has that type, whatever it starts with: K2 records what the
                // value is only for a later assignment (`val i: Int? = 1; i.inc()` is an unsafe call), and aliases
                // only `val b = a`, never `val b: T = a`.
                if (e in tracked && e.initializer != null) {
                    if (e.typeReference != null) assign(st, e, VarNul.UNKNOWN, null)
                    else assign(st, e, nulOf(e.initializer, st), aliasSource(e.initializer))
                } else st
            }
            is KtDestructuringDeclaration -> flowExpr(e.initializer, s)
            is KtNameReferenceExpression -> { record(e, s); s }
            is KtConstantExpression, is KtStringTemplateExpression -> flowChildren(e, s)
            is KtReturnExpression -> {
                val st = flowExpr(e.returnedExpression, s)
                e.getLabelName()?.let { label -> labeledLambda(e, label)?.let { lambdaExits[it] = merge(lambdaExits[it], st) } }
                null
            }
            is KtThrowExpression -> { flowExpr(e.thrownExpression, s); null }
            is KtBreakExpression -> { jumpTarget(e)?.let { breaks[it] = merge(breaks[it], s) }; null }
            is KtContinueExpression -> { jumpTarget(e)?.let { continues[it] = merge(continues[it], s) }; null }
            is KtBinaryExpression -> binary(e, s)
            is KtBinaryExpressionWithTypeRHS -> {
                val st = flowExpr(e.left, s)
                val hard = e.operationReference.getReferencedNameElementType() == KtTokens.AS_KEYWORD
                if (hard && isNonNullType(e.right?.text)) refineVar(st, bound(e.left), VarNul.NOT_NULL) else st
            }
            is KtIsExpression -> flowExpr(e.leftHandSide, s)
            is KtPostfixExpression -> postfix(e, s)
            is KtPrefixExpression -> when (e.operationToken) {
                KtTokens.PLUSPLUS, KtTokens.MINUSMINUS -> incDec(e, e.baseExpression, s)
                else -> flowExpr(e.baseExpression, s)
            }
            is KtIfExpression -> {
                val (t, f) = cond(e.condition, s)
                val thenOut = flowExpr(e.then, t)
                val elseOut = if (e.`else` != null) flowExpr(e.`else`, f) else f
                merge(thenOut, elseOut)
            }
            is KtWhenExpression -> whenExpr(e, s)
            is KtWhileExpression -> whileLoop(e, s)
            is KtDoWhileExpression -> doWhileLoop(e, s)
            is KtForExpression -> forLoop(e, s)
            is KtTryExpression -> tryExpr(e, s)
            is KtSafeQualifiedExpression -> chain(e, s).let { (done, short) -> merge(done, short) }
            is KtDotQualifiedExpression -> {
                val st = flowExpr(e.receiverExpression, s)
                val sel = e.selectorExpression
                if (sel is KtCallExpression) call(sel, st, e.receiverExpression) else flowExpr(sel, st)
            }
            is KtCallExpression -> call(e, s, null)
            is KtCallableReferenceExpression -> flowExpr(e.receiverExpression, s)
            else -> flowChildren(e, s)
        }
    }

    private fun postfix(e: KtPostfixExpression, s: FlowState?): FlowState? = when (e.operationToken) {
        KtTokens.EXCLEXCL -> {
            val base = unwrap(e.baseExpression)
            if (base is KtSafeQualifiedExpression) {
                // `a?.b!!` completes only when the whole chain ran.
                val (done, _) = chain(base, s)
                done
            } else refineVar(flowExpr(e.baseExpression, s), bound(e.baseExpression), VarNul.NOT_NULL)
        }
        KtTokens.PLUSPLUS, KtTokens.MINUSMINUS -> incDec(e, e.baseExpression, s)
        else -> flowExpr(e.baseExpression, s)
    }

    /** `x++`: reads and reassigns `x`; the new value is non-null when the old one was. */
    private fun incDec(e: KtUnaryExpression, base: KtExpression?, s: FlowState?): FlowState? {
        val st = flowExpr(base, s) ?: return null
        val v = bound(base) ?: return st
        if (!isMutable(v)) return st
        return assign(st, v, known(st, v)?.takeIf { it.nonNull } ?: VarNul.UNKNOWN, null)
    }

    private fun binary(e: KtBinaryExpression, s: FlowState?): FlowState? {
        val op = e.operationToken
        return when {
            op == KtTokens.EQ -> {
                val target = bound(e.left)
                if (target != null) {
                    val st = flowExpr(e.right, s)
                    assign(st, target, nulOf(e.right, st), aliasSource(e.right))
                } else {
                    // `a.b = c`, `a[i] = c`: the receiver and indices run first, then the value.
                    val left = unwrap(e.left)
                    val st = when (left) {
                        is KtQualifiedExpression -> flowExpr(left.receiverExpression, s)
                        is KtArrayAccessExpression -> {
                            var x = flowExpr(left.arrayExpression, s)
                            for (i in left.indexExpressions) x = flowExpr(i, x)
                            x
                        }
                        else -> s
                    }
                    if (left is KtSafeQualifiedExpression) {
                        // `a?.b = c` evaluates `c` only when `a` is non-null.
                        val recv = bound(left.receiverExpression)
                        merge(refineVar(st, recv, VarNul.NULL), flowExpr(e.right, refineVar(st, recv, VarNul.NOT_NULL)))
                    } else flowExpr(e.right, st)
                }
            }
            op in COMPOUND_ASSIGN -> {
                val target = bound(e.left)
                val st = flowExpr(e.right, flowExpr(e.left, s))
                if (target != null && isMutable(target) && st != null) {
                    // `x += y` on a `var` is `x = x + y`: what `plus` returns is not known here.
                    assign(st, target, if (declaredNonNull(target)) VarNul.NOT_NULL else VarNul.UNKNOWN, null)
                } else st
            }
            op == KtTokens.ANDAND || op == KtTokens.OROR -> cond(e, s).let { (t, f) -> merge(t, f) }
            op == KtTokens.EQEQ || op == KtTokens.EXCLEQ || op == KtTokens.EQEQEQ || op == KtTokens.EXCLEQEQEQ ->
                cond(e, s).let { (t, f) -> merge(t, f) }
            op == KtTokens.ELVIS -> {
                val left = unwrap(e.left)
                val (nonNullPath, nullPath) = if (left is KtSafeQualifiedExpression) {
                    val (done, short) = chain(left, s)
                    // The chain's value may still be null after it ran (a member that returns null).
                    done to merge(done, short)
                } else {
                    val st = flowExpr(e.left, s)
                    val v = bound(e.left)
                    refineVar(st, v, VarNul.NOT_NULL) to refineVar(st, v, VarNul.NULL)
                }
                merge(nonNullPath, flowExpr(e.right, nullPath))
            }
            else -> flowExpr(e.right, flowExpr(e.left, s))
        }
    }

    /**
     * A `?.` chain: the state once every link ran (the receiver of each link non-null), and the state when some
     * link was null and the rest were skipped.
     */
    private fun chain(e: KtSafeQualifiedExpression, s: FlowState?): Pair<FlowState?, FlowState?> {
        val recv = unwrap(e.receiverExpression)
        var short: FlowState? = null
        var st: FlowState?
        if (recv is KtSafeQualifiedExpression) {
            val (done, sh) = chain(recv, s)
            short = sh
            st = done
        } else {
            st = flowExpr(e.receiverExpression, s)
        }
        val v = bound(recv)
        // The receiver's value is null on the short path. Only a variable carries that fact.
        short = merge(short, refineVar(st, v, VarNul.NULL))
        val entered = refineVar(st, v, VarNul.NOT_NULL)
        val sel = e.selectorExpression
        val done = if (sel is KtCallExpression) call(sel, entered, e.receiverExpression) else flowExpr(sel, entered)
        return done to short
    }

    // ---- conditions ----

    /** The states after [e] evaluates to true and to false. */
    private fun cond(element: KtExpression?, s: FlowState?): Pair<FlowState?, FlowState?> {
        if (s == null) { markDead(element); return null to null }
        val e = unwrap(element) ?: return s to s
        if (e is KtConstantExpression) {
            when (e.text) {
                "true" -> return s to null
                "false" -> return null to s
            }
        }
        if (e is KtPrefixExpression && e.operationToken == KtTokens.EXCL) {
            val (t, f) = cond(e.baseExpression, s)
            return f to t
        }
        if (e is KtBinaryExpression) {
            when (e.operationToken) {
                KtTokens.ANDAND -> {
                    val (lt, lf) = cond(e.left, s)
                    val (rt, rf) = cond(e.right, lt)
                    return rt to merge(lf, rf)
                }
                KtTokens.OROR -> {
                    val (lt, lf) = cond(e.left, s)
                    val (rt, rf) = cond(e.right, lf)
                    return merge(lt, rt) to rf
                }
                KtTokens.EQEQ, KtTokens.EXCLEQ, KtTokens.EQEQEQ, KtTokens.EXCLEQEQEQ -> {
                    val (eqTrue, eqFalse) = equality(e, s)
                    val negated = e.operationToken == KtTokens.EXCLEQ || e.operationToken == KtTokens.EXCLEQEQEQ
                    return if (negated) eqFalse to eqTrue else eqTrue to eqFalse
                }
            }
        }
        if (e is KtIsExpression) {
            val lhs = unwrap(e.leftHandSide)
            val nonNullTarget = isNonNullType(e.typeReference?.text)
            val (matched, other) = if (lhs is KtSafeQualifiedExpression) {
                val (done, short) = chain(lhs, s)
                (if (nonNullTarget) done else merge(done, short)) to merge(done, short)
            } else {
                val st = flowExpr(e.leftHandSide, s)
                (if (nonNullTarget) refineVar(st, bound(lhs), VarNul.NOT_NULL) else st) to st
            }
            return if (e.isNegated) other to matched else matched to other
        }
        // `v.isNullOrEmpty()` / `v.isNullOrBlank()`: their contract makes `v` non-null when they return false.
        if (e is KtDotQualifiedExpression) {
            val name = ((e.selectorExpression as? KtCallExpression)?.calleeExpression as? KtNameReferenceExpression)?.getReferencedName()
            if ((name == "isNullOrEmpty" || name == "isNullOrBlank") && (e.selectorExpression as KtCallExpression).valueArguments.isEmpty()) {
                val st = flowExpr(e, s)
                return st to refineVar(st, bound(unwrap(e.receiverExpression)), VarNul.NOT_NULL)
            }
        }
        val st = flowExpr(e, s)
        return st to st
    }

    /** `a == b`: the states when it holds and when it does not. */
    private fun equality(e: KtBinaryExpression, s: FlowState): Pair<FlowState?, FlowState?> {
        val l = unwrap(e.left)
        val r = unwrap(e.right)
        // `x == null`: x is null when it holds, non-null when it does not; a `?.` chain compared with null was
        // completed, with its receivers non-null, when the comparison fails.
        if (isNullLit(r) || isNullLit(l)) {
            val side = if (isNullLit(r)) l else r
            if (side is KtSafeQualifiedExpression) {
                val (done, short) = chain(side, s)
                return merge(done, short) to done
            }
            val st = flowExpr(side, s)
            val v = bound(side)
            return refineVar(st, v, VarNul.NULL) to refineVar(st, v, VarNul.NOT_NULL)
        }
        // `x == <non-null value>`: x is non-null when it holds. A `?.` chain equal to a non-null value ran.
        val (lDone, lShort) = if (l is KtSafeQualifiedExpression) chain(l, s) else flowExpr(e.left, s) to null
        val leftAll = merge(lDone, lShort)
        val (rDone, rShort) = if (r is KtSafeQualifiedExpression) chain(r, leftAll) else flowExpr(e.right, leftAll) to null
        val all = merge(rDone, rShort)
        var t = all
        if (rShort == null && r !is KtSafeQualifiedExpression && nulOf(r, rDone).nonNull) {
            t = if (l is KtSafeQualifiedExpression) {
                // Only the path on which the chain ran can be equal to a non-null value.
                if (lShort == null) t else flowExprQuiet(e.right, lDone)
            } else refineVar(t, bound(l), VarNul.NOT_NULL)
        } else if (lShort == null && l !is KtSafeQualifiedExpression && nulOf(l, lDone).nonNull) {
            t = if (r is KtSafeQualifiedExpression) rDone else refineVar(t, bound(r), VarNul.NOT_NULL)
        }
        return t to all
    }

    /** Re-evaluates [e] from [s] for its effect on the state alone (its reads were recorded on the first pass). */
    private fun flowExprQuiet(e: KtExpression?, s: FlowState?): FlowState? {
        val saved = HashMap(proven)
        val out = flowExpr(e, s)
        proven.clear(); proven.putAll(saved)
        return out
    }

    // ---- when ----

    private fun whenExpr(e: KtWhenExpression, s: FlowState?): FlowState? {
        var st = s
        val subjectVar = e.subjectVariable
        if (subjectVar != null) st = flowExpr(subjectVar, st)
        else st = flowExpr(e.subjectExpression, st)
        val subject: KtElement? = subjectVar?.takeIf { it in tracked } ?: bound(e.subjectExpression)
        val hasSubject = subjectVar != null || e.subjectExpression != null
        val outs = ArrayList<FlowState?>()
        var rest = st // no earlier branch matched
        for (entry in e.entries) {
            if (entry.isElse) {
                outs += flowExpr(entry.expression, rest)
                rest = null
                continue
            }
            var matched: FlowState? = null
            var unmatched: FlowState? = rest
            for (c in entry.conditions) {
                // Each condition is tried only when the earlier ones of the entry did not match.
                val (t, f) = when {
                    !hasSubject && c is KtWhenConditionWithExpression -> cond(c.expression, unmatched)
                    c is KtWhenConditionWithExpression -> {
                        val x = unwrap(c.expression)
                        val after = flowExpr(c.expression, unmatched)
                        when {
                            isNullLit(x) -> refineVar(after, subject, VarNul.NULL) to refineVar(after, subject, VarNul.NOT_NULL)
                            nulOf(x, after).nonNull -> refineVar(after, subject, VarNul.NOT_NULL) to after
                            else -> after to after
                        }
                    }
                    c is KtWhenConditionIsPattern -> {
                        val nonNull = isNonNullType(c.typeReference?.text)
                        val hit = if (nonNull) refineVar(unmatched, subject, VarNul.NOT_NULL) else unmatched
                        if (c.isNegated) unmatched to hit else hit to unmatched
                    }
                    else -> flowElement(c, unmatched).let { it to it }
                }
                matched = merge(matched, t)
                unmatched = f
            }
            entry.guard?.let { g ->
                val (gt, gf) = cond(g, matched)
                matched = gt
                unmatched = merge(unmatched, gf)
            }
            outs += flowExpr(entry.expression, matched)
            rest = unmatched
        }
        // With no `else`, no branch may match. A `when` used as a value, or one Kotlin requires to be
        // exhaustive, has no such path; one written as a statement over an enum or sealed subject may still be
        // exhaustive, which is not decided here, so its no-match path is kept (that proves less, never more).
        if (rest != null && !usedAsValue(e)) outs += rest
        return mergeAll(outs)
    }

    private fun usedAsValue(e: KtExpression): Boolean {
        var child: KtElement = e
        var p = e.parent
        while (p is KtParenthesizedExpression || p is KtAnnotatedExpression || p is KtLabeledExpression) { child = p; p = p.parent }
        return when (p) {
            is KtBlockExpression -> {
                // The last statement of a lambda is its value only when the lambda's result is used, which is
                // not known here.
                false
            }
            null -> false
            is KtIfExpression, is KtWhenExpression -> false
            else -> p !is KtLoopExpression && p !is KtTryExpression && p !is dev.ide.kotlin.syntax.psi.KtWhenEntry &&
                p !is dev.ide.kotlin.syntax.psi.KtContainerNode
        }
    }

    // ---- loops ----

    private fun whileLoop(e: KtWhileExpression, s: FlowState?): FlowState? {
        val condEnter = havoc(s, e)
        val (t, f) = cond(e.condition, condEnter)
        val bodyOut = flowExpr(e.body, t)
        val back = listOf(bodyOut, continues.remove(e))
        val brk = breaks.remove(e)
        // K2 commits "the condition is false" at the exit only when nothing else (a `break`) reaches it.
        val condExit = if (isTrue(e.condition)) null else if (brk == null) f else merge(t, f)
        return restoreAfterLoop(e, s, condEnter, back, listOf(condExit, brk))
    }

    private fun doWhileLoop(e: KtDoWhileExpression, s: FlowState?): FlowState? {
        val entry = havoc(s, e)
        val bodyOut = flowExpr(e.body, entry)
        val (t, f) = cond(e.condition, merge(bodyOut, continues.remove(e)))
        val brk = breaks.remove(e)
        val condExit = if (isTrue(e.condition)) null else if (brk == null) f else merge(t, f)
        return merge(condExit, brk)
    }

    private fun forLoop(e: KtForExpression, s: FlowState?): FlowState? {
        val afterRange = flowExpr(e.loopRange, s)
        // A `for` is a `while (iterator.hasNext())`, whose condition says nothing about the variables.
        val condEnter = havoc(afterRange, e)
        val bodyOut = flowExpr(e.body, condEnter)
        val back = listOf(bodyOut, continues.remove(e))
        return restoreAfterLoop(e, afterRange, condEnter, back, listOf(condEnter, breaks.remove(e)))
    }

    /**
     * The state after a `while` or `for` loop, from its [exits] (the condition turning false, each `break`).
     * Entering the loop forgot what it reassigns; once the back edges are known that can be given back (K2's
     * `processWhileLoopExit`): a variable that every way into the condition ([before] the loop, the end of the
     * body, each `continue`) knows the same thing about still has that fact at an exit where it holds the value
     * it had when the condition was entered.
     */
    private fun restoreAfterLoop(
        loop: KtElement, before: FlowState?, condEnter: FlowState?, back: List<FlowState?>, exits: List<FlowState?>,
    ): FlowState? {
        val reassigned = assignedIn(loop, plainOnly = true)
        if (condEnter == null || reassigned.isEmpty()) return mergeAll(exits)
        val into = (listOf(before) + back).filterNotNull()
        val toAdd = HashMap<KtElement, VarNul>()
        for (v in reassigned) {
            val facts = into.map { if (v in it.unstable) null else it.facts[v] }
            if (facts.isEmpty()) continue
            facts.reduce { a, b -> joinNul(a, b) }?.let { toAdd[v] = it }
        }
        if (toAdd.isEmpty()) return mergeAll(exits)
        return mergeAll(exits.map { x ->
            if (x == null) return@map null
            var out: FlowState = x
            for ((v, fact) in toAdd) {
                if (v in out.unstable || out.facts[v] != null || x.ver[v] != condEnter.ver[v]) continue
                out = FlowState(out.facts + (v to fact), out.alias, out.unstable, out.ver)
            }
            out
        })
    }

    /** The loop a `break`/`continue` leaves or repeats: the labeled one, or the nearest around it. */
    private fun jumpTarget(e: KtExpressionWithLabel): KtElement? {
        val label = e.getLabelName()
        var node: KtElement? = e.parent
        while (node != null && node !is KtClassOrObject) {
            if (node is KtLoopExpression) {
                if (label == null) return node
                val l = node.parent as? KtLabeledExpression
                if (l?.getLabelName() == label) return node
            }
            if (label == null && (node is KtFunctionLiteral || node is KtNamedFunction)) return null
            node = node.parent
        }
        return null
    }

    // ---- try ----

    private fun tryExpr(e: KtTryExpression, s: FlowState?): FlowState? {
        val tryOut = flowExpr(e.tryBlock, s)
        // A catch is entered from anywhere in the try block.
        val catchEntry = havoc(s, e.tryBlock, plainOnly = false)
        val outs = arrayListOf(tryOut)
        for (cc in e.catchClauses) outs += flowExpr(cc.catchBody, catchEntry)
        val normal = mergeAll(outs)
        val fin = e.finallyBlock?.finalExpression ?: return normal
        // The finally block also runs after an exception from anywhere above, so its reads are judged in that
        // state as well; the code after the whole `try` continues from the normal path through it.
        flowExpr(fin, merge(normal, havoc(s, e, plainOnly = false)))
        if (normal == null) return null
        // A read visited twice keeps the weaker verdict, so this pass cannot undo the conservative one.
        return flowExpr(fin, normal)
    }

    // ---- calls and lambdas ----

    private fun call(e: KtCallExpression, s: FlowState?, receiver: KtExpression?): FlowState? {
        var st = s
        val callee = e.calleeExpression
        if (callee != null && callee !is KtNameReferenceExpression) st = flowExpr(callee, st)
        val calleeName = (callee as? KtNameReferenceExpression)?.getReferencedName()
        val beforeArgs = st
        // Lambdas called in place by one call run in an order the call decides: what one assigns is unknown
        // inside the others (K2 draws an edge to each from before the call).
        val inPlace = e.valueArguments.mapNotNull { a -> lambdaOf(a.getArgumentExpression())?.takeIf { inPlaceKind(it) != null } }
        for (arg in e.valueArguments) {
            val x = arg.getArgumentExpression() ?: continue
            val lambda = lambdaOf(x)
            st = if (lambda != null) {
                val kind = inPlaceKind(lambda)
                if (kind != null) {
                    val siblings = if (inPlace.size > 1) inPlace.filter { it !== lambda }.flatMapTo(HashSet()) { assignedIn(it) } else emptySet()
                    inPlaceLambda(lambda, kind, forget(st, siblings))
                } else flowExpr(x, st)
            } else flowExpr(x, st)
        }
        if (st == null) return null
        if (receiver == null) {
            val first = e.valueArguments.takeIf { it.size <= 2 }?.firstOrNull()?.getArgumentExpression()
            when (calleeName) {
                "requireNotNull", "checkNotNull" -> st = refineVar(st, bound(first), VarNul.NOT_NULL)
                "require", "check", "assert" -> if (first != null && lambdaOf(first) == null) {
                    // The condition already ran; replay it for what it proves when it holds.
                    st = condQuiet(first, beforeArgs) ?: st
                }
            }
        }
        if (flow.liveness(if (receiver != null) e.parent as? KtExpression ?: e else e) == Liveness.DEAD &&
            e.valueArguments.none { lambdaOf(it.getArgumentExpression()) != null }) return null
        return st
    }

    /** The true-state of [c] evaluated from [s], without recording its reads again. */
    private fun condQuiet(c: KtExpression, s: FlowState?): FlowState? {
        val saved = HashMap(proven)
        val (t, _) = cond(c, s)
        proven.clear(); proven.putAll(saved)
        return t
    }

    private fun lambdaOf(e: KtExpression?): KtLambdaExpression? {
        var x = e
        while (x is KtLabeledExpression || x is KtAnnotatedExpression || x is KtParenthesizedExpression) {
            x = when (x) {
                is KtLabeledExpression -> x.baseExpression
                is KtAnnotatedExpression -> x.baseExpression
                is KtParenthesizedExpression -> x.expression
                else -> null
            }
        }
        return x as? KtLambdaExpression
    }

    private fun inPlaceLambda(lambda: KtLambdaExpression, kind: InPlace, s: FlowState?): FlowState? {
        if (s == null) { markDead(lambda); return null }
        val body = lambda.bodyExpression ?: return s
        val repeats = kind == InPlace.UNKNOWN || kind == InPlace.AT_LEAST_ONCE
        val entry = if (repeats) havoc(s, body) else s
        val out = merge(flowExpr(body, entry), lambdaExits.remove(lambda.functionLiteral))
        // A call nested in another call's arguments is resolved with that call, and Kotlin carries nothing its
        // lambda proves out of it: what follows sees the lambda as one that may not have run.
        // What it assigns is not stable afterwards either (K2's data-flow-postponed lambdas).
        val call = callOf(lambda)
        if (call != null && !standsAlone(call)) return forget(merge(s, out), emptySet(), assignedIn(body).filterTo(HashSet()) { isMutable(it) })
        return when (kind) {
            InPlace.EXACTLY_ONCE, InPlace.AT_LEAST_ONCE -> out
            InPlace.AT_MOST_ONCE, InPlace.UNKNOWN -> merge(s, out)
        }
    }

    /** Whether [call] is resolved on its own: no value argument or `when` subject between it and its statement. */
    private fun standsAlone(call: KtElement): Boolean {
        var child: KtElement = call
        var node = call.parent
        while (node != null && node !is KtBlockExpression) {
            if (node is KtValueArgument || node is KtFunctionLiteral || node is KtDeclarationWithBody) return false
            if (node is KtWhenExpression && child !is dev.ide.kotlin.syntax.psi.KtWhenEntry) return false
            child = node
            node = node.parent
        }
        return true
    }

    /** A lambda that is not called in place: it may run at any later time, any number of times. */
    private fun concurrentLambda(lambda: KtLambdaExpression, s: FlowState?): FlowState? =
        concurrentClosure(lambda, lambda.bodyExpression, s)

    private fun concurrentClosure(closure: KtElement, body: KtExpression?, s: FlowState?): FlowState? {
        if (s == null) { markDead(closure); return null }
        if (body != null) {
            // Inside, an outer variable keeps what is known of it only when nothing can change it once the
            // closure exists: nothing assigns it afterwards, and no closure assigns it at all.
            // An assignment that comes after the closure in the code is not resolved yet when the closure is,
            // so K2 cannot tell what it assigns and counts it against the smart cast whatever it is.
            val volatile = HashSet<KtElement>()
            for (v in s.facts.keys) if (isMutable(v) && (v in allConcurrentWrites || assignedAfter(v, closure))) volatile += v
            // What the closure itself writes is unstable inside it too: it may be running twice at once.
            val writes = HashSet<KtElement>()
            destabilizingWritesIn(closure, writes)
            flowExpr(body, forget(s, volatile + assignedIn(body), writes))
        }
        val writes = HashSet<KtElement>()
        destabilizingWritesIn(closure, writes)
        return forget(s, emptySet(), writes)
    }

    /** The label a `return@label` returns to: an in-place lambda labeled so, or passed to a call of that name. */
    private fun labeledLambda(e: KtReturnExpression, label: String): KtElement? {
        var node: KtElement? = e.parent
        while (node != null && node !is KtClassOrObject) {
            if (node is KtNamedFunction) return null
            if (node is KtFunctionLiteral) {
                val lambda = node.parent as? KtLambdaExpression
                val labeled = lambda?.parent as? KtLabeledExpression
                if (labeled?.getLabelName() == label) return node
                val call = lambda?.let { callOf(it) }
                if ((call?.calleeExpression as? KtNameReferenceExpression)?.getReferencedName() == label) return node
            }
            node = node.parent
        }
        return null
    }

    private fun callOf(lambda: KtLambdaExpression): KtCallExpression? {
        var arg: KtElement = lambda
        while (arg.parent is KtLabeledExpression || arg.parent is KtAnnotatedExpression || arg.parent is KtParenthesizedExpression) arg = arg.parent as KtElement
        return when (val p = arg.parent) {
            is KtLambdaArgument -> p.parent as? KtCallExpression
            is KtValueArgument -> (p.parent as? KtValueArgumentList)?.parent as? KtCallExpression
            else -> null
        }
    }

    /**
     * How the function [lambda] is passed to calls it, or null when it is not known to call it in place. A
     * source function says so with a `callsInPlace` contract or by being `inline` (a lambda parameter of an inline
     * function is inlined at the call, unless `noinline` or `crossinline`); the stdlib's scope functions are
     * known by name, and any other library function by whether it is `inline`.
     */
    internal fun inPlaceKind(lambda: KtLambdaExpression): InPlace? = inPlaceCache.getOrPut(lambda) {
        val call = callOf(lambda) ?: return@getOrPut null
        if (call.calleeExpression !is KtNameReferenceExpression) return@getOrPut null
        val name = (call.calleeExpression as KtNameReferenceExpression).getReferencedName()
        val sym = runCatching { resolver.resolveCalleeFunction(call) }.getOrNull()
        if (sym != null && sym.origin.fromSource) {
            val fn = (sym.declaration() as? dev.ide.lang.kotlin.parse.KotlinDomNode)?.psi as? KtNamedFunction
                ?: return@getOrPut null
            val argIndex = call.valueArguments.indexOfFirst { lambdaOf(it.getArgumentExpression()) === lambda }
            val param = fn.valueParameters.getOrNull(resolver.lambdaParamIndex(call, argIndex, sym)) ?: return@getOrPut null
            contractKind(fn, param.name)?.let { return@getOrPut it }
            if (!fn.hasModifier(KtTokens.INLINE_KEYWORD)) return@getOrPut null
            if (param.hasModifier(KtTokens.NOINLINE_KEYWORD) || param.hasModifier(KtTokens.CROSSINLINE_KEYWORD)) return@getOrPut null
            val type = param.typeReference?.typeElement
            if (type is KtNullableType) return@getOrPut null
            val typeName = (type as? dev.ide.kotlin.syntax.psi.KtUserType)?.referenceExpression?.getReferencedName()
            if (typeName != null && fn.typeParameters.any { it.name == typeName }) return@getOrPut null
            return@getOrPut InPlace.UNKNOWN
        }
        val fromStdlib = sym == null || sym.packageName?.startsWith("kotlin") == true
        if (fromStdlib) STDLIB_IN_PLACE[name]?.let { return@getOrPut it }
        if (sym != null && sym.isInline) InPlace.UNKNOWN else null
    }

    /** The invocation kind a `contract { callsInPlace(param, InvocationKind.X) }` in [fn] declares for [param]. */
    private fun contractKind(fn: KtNamedFunction, param: String?): InPlace? {
        if (param == null) return null
        val body = fn.bodyBlockExpression ?: return null
        val first = body.statements.firstOrNull() as? KtCallExpression ?: return null
        if ((first.calleeExpression as? KtNameReferenceExpression)?.getReferencedName() != "contract") return null
        val text = first.text ?: return null
        val m = Regex("""callsInPlace\s*\(\s*$param\s*(?:,\s*(?:[\w.]*\.)?(\w+))?\s*\)""").find(text) ?: return null
        return when (m.groupValues[1]) {
            "EXACTLY_ONCE" -> InPlace.EXACTLY_ONCE
            "AT_LEAST_ONCE" -> InPlace.AT_LEAST_ONCE
            "AT_MOST_ONCE" -> InPlace.AT_MOST_ONCE
            else -> InPlace.UNKNOWN
        }
    }

    // ---- assignment summaries ----

    /** The tracked variables assigned anywhere in [region], closures included; with [plainOnly], only those
     *  assigned with a plain `=` somewhere in it. */
    private fun assignedIn(region: KtElement, plainOnly: Boolean = false): Set<KtElement> = assignedCache.getOrPut(region to plainOnly) {
        val out = HashSet<KtElement>()
        fun rec(p: KtElement) {
            when (p) {
                is KtBinaryExpression -> if (p.operationToken == KtTokens.EQ || (!plainOnly && p.operationToken in COMPOUND_ASSIGN)) bound(p.left)?.let { out += it }
                is KtUnaryExpression -> if (!plainOnly && (p.operationToken == KtTokens.PLUSPLUS || p.operationToken == KtTokens.MINUSMINUS)) bound(p.baseExpression)?.let { out += it }
            }
            var c = p.firstChild
            while (c != null) { rec(c); c = c.nextSibling }
        }
        rec(region)
        out
    }

    /** The variables written by a closure inside [region] that is not called in place. */
    private fun concurrentWritesIn(region: KtElement): Set<KtElement> = concurrentWritesCache.getOrPut(region) {
        val out = HashSet<KtElement>()
        fun rec(p: KtElement) {
            val closure = when (p) {
                is KtLambdaExpression -> inPlaceKind(p) == null
                is KtNamedFunction, is KtClassOrObject -> true
                else -> false
            }
            if (closure) { destabilizingWritesIn(p, out); return }
            var c = p.firstChild
            while (c != null) { rec(c); c = c.nextSibling }
        }
        rec(region)
        out
    }

    private val allConcurrentWrites: Set<KtElement> by lazy { concurrentWritesIn(rootBody) }

    /**
     * The `var`s [region] assigns a value that may be null. K2 keeps a variable that a concurrent lambda writes
     * stable as long as every such write preserves the smart cast in question (`allAssignmentsPreserveType`); for
     * nullness that means assigning a non-null value.
     */
    private fun destabilizingWritesIn(region: KtElement, out: MutableSet<KtElement>) {
        // A variable declared inside the region is its own: the region's flow tracks it.
        val r = region.textRange
        fun outside(v: KtElement) = v.textRange.let { it.startOffset < r.startOffset || it.endOffset > r.endOffset }
        fun rec(p: KtElement) {
            when (p) {
                is KtBinaryExpression -> if (p.operationToken == KtTokens.EQ || p.operationToken in COMPOUND_ASSIGN) {
                    bound(p.left)?.takeIf { isMutable(it) && outside(it) }?.let { v ->
                        val keeps = if (p.operationToken == KtTokens.EQ) nulOf(p.right, null).nonNull else declaredNonNull(v)
                        if (!keeps) out += v
                    }
                }
                is KtUnaryExpression -> if (p.operationToken == KtTokens.PLUSPLUS || p.operationToken == KtTokens.MINUSMINUS) {
                    bound(p.baseExpression)?.takeIf { isMutable(it) && outside(it) && !declaredNonNull(it) }?.let { out += it }
                }
            }
            var c = p.firstChild
            while (c != null) { rec(c); c = c.nextSibling }
        }
        rec(region)
    }

    /** Whether [v] is assigned, outside [closure], at a point that can run after [closure] is created: later in
     *  the text, or anywhere in a loop around it. */
    private fun assignedAfter(v: KtElement, closure: KtElement): Boolean {
        val start = closure.textRange.startOffset
        val end = closure.textRange.endOffset
        var loopStart = Int.MAX_VALUE
        var node: KtElement? = closure.parent
        val decl = v.textRange
        while (node != null && node !== rootBody.parent) {
            // A loop that also holds the declaration makes a new variable on every iteration: the next
            // iteration's assignments are to that one.
            val r = node.textRange
            if (r.startOffset <= decl.startOffset && decl.endOffset <= r.endOffset) break
            if (node is KtLoopExpression) loopStart = minOf(loopStart, node.textRange.startOffset)
            node = node.parent
        }
        val from = minOf(loopStart, end)
        var found = false
        fun rec(p: KtElement) {
            if (found) return
            val r = p.textRange
            if (r.endOffset <= from) return
            if (r.startOffset >= start && r.endOffset <= end) return
            val target = when (p) {
                is KtBinaryExpression -> if (p.operationToken == KtTokens.EQ || p.operationToken in COMPOUND_ASSIGN) p.left else null
                is KtUnaryExpression -> if (p.operationToken == KtTokens.PLUSPLUS || p.operationToken == KtTokens.MINUSMINUS) p.baseExpression else null
                else -> null
            }
            if (target != null && r.startOffset >= from && bound(target) == v) { found = true; return }
            var c = p.firstChild
            while (c != null) { rec(c); c = c.nextSibling }
        }
        rec(rootBody)
        return found
    }

    // ---- values ----

    /** The variable a value expression reads directly (`var b = a`), which the assignment makes an alias of. */
    private fun aliasSource(e: KtExpression?): KtElement? = bound(e)

    /** What is known of the nullness of the value [expr] evaluates to, in the state [s] after it ran. */
    private fun nulOf(expr: KtExpression?, s: FlowState?): VarNul {
        val e = unwrap(expr) ?: return VarNul.UNKNOWN
        return when (e) {
            is KtConstantExpression -> if (e.text == "null") VarNul.NULL else VarNul.NOT_NULL
            is KtStringTemplateExpression, is KtLambdaExpression, is KtObjectLiteralExpression,
            is KtCallableReferenceExpression, is KtIsExpression -> VarNul.NOT_NULL
            is KtPostfixExpression -> if (e.operationToken == KtTokens.EXCLEXCL) VarNul.NOT_NULL else typeNul(e)
            is KtNameReferenceExpression -> {
                val v = bound(e)
                if (v != null && s != null && known(s, v) != null) known(s, v)!! else typeNul(e)
            }
            is KtBinaryExpression -> when (e.operationToken) {
                KtTokens.EQEQ, KtTokens.EXCLEQ, KtTokens.EQEQEQ, KtTokens.EXCLEQEQEQ, KtTokens.ANDAND, KtTokens.OROR,
                KtTokens.LT, KtTokens.GT, KtTokens.LTEQ, KtTokens.GTEQ -> VarNul.NOT_NULL
                KtTokens.ELVIS -> if (nulOf(e.right, s).nonNull) VarNul.NN_INFERRED else typeNul(e)
                else -> typeNul(e)
            }
            is KtBinaryExpressionWithTypeRHS ->
                if (e.operationReference.getReferencedNameElementType() == KtTokens.AS_KEYWORD && isNonNullType(e.right?.text)) VarNul.NOT_NULL
                else VarNul.UNKNOWN
            is KtIfExpression -> {
                val t = e.then
                val f = e.`else`
                if (t != null && f != null) joinNul(valueNul(t, s), valueNul(f, s))?.takeIf { it.nonNull } ?: typeNul(e)
                else typeNul(e)
            }
            is KtSafeQualifiedExpression -> VarNul.UNKNOWN
            else -> typeNul(e)
        }
    }

    /** The nullness of a branch's value: its last statement when it is a block (a jump gives no value). */
    private fun valueNul(branch: KtExpression, s: FlowState?): VarNul {
        val e = unwrap(branch)
        val last = if (e is KtBlockExpression) e.statements.lastOrNull() as? KtExpression ?: return VarNul.UNKNOWN else e
        if (last is KtReturnExpression || last is KtThrowExpression || last is KtBreakExpression || last is KtContinueExpression) return VarNul.NOT_NULL
        // The state after the branch is not at hand; only literals and declared types speak here.
        return when (val x = unwrap(last)) {
            is KtNameReferenceExpression -> bound(x)?.let { if (declaredNonNull(it)) VarNul.NOT_NULL else VarNul.UNKNOWN } ?: typeNul(x)
            else -> nulOf(x, null)
        }
    }

    private fun typeNul(e: KtExpression): VarNul {
        val t = runCatching { resolver.inferType(e) }.getOrNull() ?: return VarNul.UNKNOWN
        if (t.nullable || t.isTypeParameter) return VarNul.UNKNOWN
        if (t.qualifiedName == "kotlin.Nothing") return VarNul.UNKNOWN
        return VarNul.NN_INFERRED
    }

    /** Whether [v] is declared with a non-null type (spelled out, or inferred from its initializer). */
    private fun declaredNonNull(v: KtElement): Boolean {
        val typeText = when (v) {
            is KtProperty -> v.typeReference?.text
            is KtParameter -> v.typeReference?.text
            else -> null
        }
        val t = typeText?.trim() ?: return false
        if (t.endsWith("?")) return false
        // A type parameter may stand for a nullable type.
        var node: KtElement? = v.parent
        while (node != null && node !is KtFile) {
            if (node is dev.ide.kotlin.syntax.psi.KtTypeParameterListOwner && node.typeParameters.any { it.name == t }) return false
            node = node.parent
        }
        return true
    }

    // ---- helpers ----

    private fun isNullLit(e: KtElement?): Boolean = e is KtConstantExpression && e.text == "null"
    private fun isTrue(e: KtExpression?): Boolean = (unwrap(e) as? KtConstantExpression)?.text == "true"
    private fun isNonNullType(typeText: String?): Boolean = typeText != null && !typeText.trim().endsWith("?")

    private fun unwrap(e: KtExpression?): KtExpression? = when (e) {
        is KtParenthesizedExpression -> unwrap(e.expression)
        is KtAnnotatedExpression -> unwrap(e.baseExpression)
        is KtLabeledExpression -> unwrap(e.baseExpression)
        else -> e
    }

    private companion object {
        val COMPOUND_ASSIGN = setOf(KtTokens.PLUSEQ, KtTokens.MINUSEQ, KtTokens.MULTEQ, KtTokens.DIVEQ, KtTokens.PERCEQ)

        /** The stdlib functions that call a lambda argument in place, by their `callsInPlace` contracts. */
        val STDLIB_IN_PLACE: Map<String, InPlace> = buildMap {
            for (n in listOf(
                "run", "with", "apply", "also", "let", "takeIf", "takeUnless", "use", "synchronized", "buildString",
                "buildList", "buildSet", "buildMap", "runCatching", "measureTimeMillis", "measureNanoTime", "measureTime",
                "measureTimedValue", "contract", "withLock", "read", "write", "useLines", "inTopDownOrder",
            )) put(n, InPlace.EXACTLY_ONCE)
            for (n in listOf("require", "check", "requireNotNull", "checkNotNull", "assert")) put(n, InPlace.AT_MOST_ONCE)
            put("repeat", InPlace.UNKNOWN)
        }
    }
}
