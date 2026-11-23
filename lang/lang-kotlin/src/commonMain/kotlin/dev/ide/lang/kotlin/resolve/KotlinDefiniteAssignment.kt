package dev.ide.lang.kotlin.resolve

import dev.ide.kotlin.syntax.psi.KtAnnotatedExpression
import dev.ide.kotlin.syntax.psi.KtBinaryExpression
import dev.ide.kotlin.syntax.psi.KtBlockExpression
import dev.ide.kotlin.syntax.psi.KtBreakExpression
import dev.ide.kotlin.syntax.psi.KtCallExpression
import dev.ide.kotlin.syntax.psi.KtCallableReferenceExpression
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
import dev.ide.kotlin.syntax.psi.KtObjectLiteralExpression
import dev.ide.kotlin.syntax.psi.KtParenthesizedExpression
import dev.ide.kotlin.syntax.psi.KtProperty
import dev.ide.kotlin.syntax.psi.KtQualifiedExpression
import dev.ide.kotlin.syntax.psi.KtReturnExpression
import dev.ide.kotlin.syntax.psi.KtThrowExpression
import dev.ide.kotlin.syntax.psi.KtTokens
import dev.ide.kotlin.syntax.psi.KtTryExpression
import dev.ide.kotlin.syntax.psi.KtUnaryExpression
import dev.ide.kotlin.syntax.psi.KtValueArgumentName
import dev.ide.kotlin.syntax.psi.KtWhenExpression
import dev.ide.kotlin.syntax.psi.KtWhileExpression

/**
 * Reads of a local variable that is not definitely assigned where it is read: Kotlin's UNINITIALIZED_VARIABLE.
 * A forward MUST-analysis over the body: the state is the set of variables assigned on every path to this
 * point, joined by intersection; a point no path reaches has no state, and nothing read there is reported.
 *
 * Loops need no fixpoint here. Assignments only ever add to the set, so the first iteration, entered with the
 * state from before the loop, is the one every later iteration improves on: a read in the body is judged in that
 * state (`for (…) { use(x); x = 1 }` reads `x` unassigned the first time round). After a loop come its exits: the
 * condition turning false (none for `while (true)`) and each `break`.
 *
 * A lambda or local function is not entered, and what it assigns to an outer variable is not counted after it
 * (whether and when it runs is the callee's business). So that this never reports a read such an assignment did
 * in fact precede, a variable assigned from inside a closure is not judged at all.
 */
internal class KotlinDefiniteAssignment(resolver: KotlinResolver) {
    private val flow = KotlinControlFlow(resolver)
    private val tracked = HashSet<KtProperty>()
    private val out = LinkedHashSet<KtNameReferenceExpression>()
    private val breaks = HashMap<KtElement, Set<KtProperty>?>()
    private val continues = HashMap<KtElement, Set<KtProperty>?>()
    private var quiet = 0

    fun uninitializedReads(body: KtBlockExpression): List<KtNameReferenceExpression> {
        collect(body)
        excludeClosureAssigned(body)
        if (tracked.isEmpty()) return emptyList()
        walk(body, emptySet())
        return out.toList()
    }

    /** Locals declared without a value, and those declared at the top of a `do`-`while` body (its condition can
     *  be reached through a `continue` that skipped the declaration). */
    private fun collect(root: KtElement) {
        if (root is KtProperty && root.parent is KtBlockExpression && !root.hasDelegate() && root.getter == null &&
            !root.hasModifier(KtTokens.LATEINIT_KEYWORD) && root.name != null &&
            (!root.hasInitializer() || (root.parent?.parent?.parent as? KtDoWhileExpression)?.body === root.parent)
        ) tracked += root
        if (root is KtClassOrObject) return
        var c = root.firstChild
        while (c != null) { collect(c); c = c.nextSibling }
    }

    /** Drops a variable assigned from inside a closure that does not also declare it. */
    private fun excludeClosureAssigned(root: KtElement) {
        fun rec(p: KtElement) {
            if (p is KtBinaryExpression && p.operationToken in ASSIGN_TOKENS) {
                bound(p.left)?.let { v -> if (closureOf(p) != closureOf(v)) tracked -= v }
            }
            if (p is KtUnaryExpression && (p.operationToken == KtTokens.PLUSPLUS || p.operationToken == KtTokens.MINUSMINUS)) {
                bound(p.baseExpression)?.let { v -> if (closureOf(p) != closureOf(v)) tracked -= v }
            }
            var c = p.firstChild
            while (c != null) { rec(c); c = c.nextSibling }
        }
        rec(root)
    }

    private fun closureOf(e: KtElement): KtElement? {
        var node = e.parent
        while (node != null) {
            if (node is KtFunctionLiteral || node is KtNamedFunction || node is KtClassOrObject) return node
            node = node.parent
        }
        return null
    }

    private fun join(a: Set<KtProperty>?, b: Set<KtProperty>?): Set<KtProperty>? =
        if (a == null) b else if (b == null) a else a intersect b

    private fun walkChildren(e: KtElement, s: Set<KtProperty>?): Set<KtProperty>? {
        var st = s
        var c = e.firstChild
        while (c != null) {
            st = when (c) {
                is KtExpression -> walk(c, st)
                is KtClassOrObject, is KtValueArgumentName, is dev.ide.kotlin.syntax.psi.KtTypeReference -> st
                else -> walkChildren(c, st)
            }
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
            // Not entered: when, and whether, it runs is the callee's business.
            is KtLambdaExpression, is KtNamedFunction, is KtObjectLiteralExpression, is KtClassOrObject -> s
            is KtBlockExpression -> {
                var st: Set<KtProperty>? = s
                for (x in e.statements) st = walk(x, st)
                st
            }
            is KtProperty -> {
                val st = walk(e.initializer, s) ?: return null
                if (e in tracked && e.initializer != null) st + e else if (e in tracked) st - e else st
            }
            is KtNameReferenceExpression -> {
                val v = bound(e)
                if (v != null && v !in s && quiet == 0) out += e
                s
            }
            is KtCallableReferenceExpression -> walk(e.receiverExpression, s)
            is KtBinaryExpression -> when (e.operationToken) {
                in ASSIGN_TOKENS -> {
                    val v = bound(e.left)
                    if (v == null) walk(e.right, walk(e.left, s))
                    else {
                        // `x += 1` reads `x` first.
                        val st = if (e.operationToken != KtTokens.EQ) walk(e.left, s) else s
                        walk(e.right, st)?.let { it + v }
                    }
                }
                KtTokens.ANDAND, KtTokens.OROR, KtTokens.ELVIS -> {
                    val l = walk(e.left, s)
                    join(l, walk(e.right, l))
                }
                else -> walk(e.right, walk(e.left, s))
            }
            is KtIfExpression -> {
                val c = walk(e.condition, s)
                join(walk(e.then, c), if (e.`else` != null) walk(e.`else`, c) else c)
            }
            is KtWhenExpression -> {
                var st = walk(e.subjectVariable ?: e.subjectExpression, s)
                var result: Set<KtProperty>? = null
                var any = false
                for (entry in e.entries) {
                    for (c in entry.conditions) st = walkChildren(c, st)
                    entry.guard?.let { st = walk(it, st) }
                    result = join(result, walk(entry.expression, st))
                    any = true
                }
                // A `when` with no `else` whose branches all assign is read as assigning: it may be exhaustive
                // over a sealed or enum subject, which this pass cannot tell (see the old pass's note).
                if (!any) st else result
            }
            is KtReturnExpression -> { walk(e.returnedExpression, s); null }
            is KtThrowExpression -> { walk(e.thrownExpression, s); null }
            is KtBreakExpression -> { jumpTarget(e)?.let { breaks[it] = join(breaks[it], s) }; null }
            is KtContinueExpression -> { jumpTarget(e)?.let { continues[it] = join(continues[it], s) }; null }
            is KtWhileExpression -> {
                val c = walk(e.condition, s)
                walk(e.body, c)
                continues.remove(e)
                join(if (isTrue(e.condition)) null else c, breaks.remove(e))
            }
            is KtDoWhileExpression -> {
                val body = walk(e.body, s)
                val c = walk(e.condition, join(body, continues.remove(e)))
                join(if (isTrue(e.condition)) null else c, breaks.remove(e))
            }
            is KtForExpression -> {
                val r = walk(e.loopRange, s)
                walk(e.body, r)
                continues.remove(e)
                join(r, breaks.remove(e))
            }
            is KtTryExpression -> {
                val tryOut = walk(e.tryBlock, s)
                // A catch or finally may follow an exception thrown before anything in the try block ran.
                var normal = tryOut
                for (cc in e.catchClauses) normal = join(normal, walk(cc.catchBody, s))
                val fin = e.finallyBlock?.finalExpression ?: return normal
                walk(fin, s)
                if (normal == null) null else { quiet++; try { walk(fin, normal) } finally { quiet-- } }
            }
            // A call that never returns (`error()`, `TODO()`, a `Nothing` function) leaves what follows unreached.
            is KtCallExpression, is KtDotQualifiedExpression ->
                walkChildren(e, s)?.takeIf { flow.liveness(e) != Liveness.DEAD }
            else -> walkChildren(e, s)
        }
    }

    /** The tracked local a bare name binds to, honouring shadowing by an inner declaration. */
    private fun bound(e: KtExpression?): KtProperty? {
        val ref = unwrap(e) as? KtNameReferenceExpression ?: return null
        val parent = ref.parent
        if (parent is KtQualifiedExpression && parent.selectorExpression === ref) return null
        if (parent is KtValueArgumentName || parent is KtCallableReferenceExpression) return null
        val name = ref.getReferencedName()
        val offset = ref.textRange.startOffset
        var node: KtElement? = ref.parent
        while (node != null) {
            when (node) {
                is KtBlockExpression -> node.statements.firstOrNull {
                    it is KtProperty && it.name == name && it.textRange.endOffset <= offset
                }?.let { return (it as KtProperty).takeIf { p -> p in tracked } }
                // A `do`-`while` condition sees the declarations of its body.
                is KtDoWhileExpression -> if (node.condition?.textRange?.let { offset >= it.startOffset && offset < it.endOffset } == true) {
                    (node.body as? KtBlockExpression)?.statements?.firstOrNull { it is KtProperty && it.name == name }
                        ?.let { return (it as KtProperty).takeIf { p -> p in tracked } }
                }
                is KtFunctionLiteral -> if (node.valueParameters.any { it.name == name } ||
                    (!node.hasParameterSpecification && name == "it")) return null
                is dev.ide.kotlin.syntax.psi.KtFunction -> if (node.valueParameters.any { it.name == name }) return null
                is KtForExpression -> if (node.loopParameter?.name == name) return null
                is dev.ide.kotlin.syntax.psi.KtCatchClause -> if (node.catchParameter?.name == name) return null
                is KtClassOrObject -> return null
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

    private companion object {
        val ASSIGN_TOKENS = setOf(KtTokens.EQ, KtTokens.PLUSEQ, KtTokens.MINUSEQ, KtTokens.MULTEQ, KtTokens.DIVEQ, KtTokens.PERCEQ)
    }
}

/**
 * Reads of a member (or top-level) property before the constructor (or the file's initialization) has given it a
 * value: Kotlin's UNINITIALIZED_VARIABLE for properties. [declarations] are walked in order, as they run: a
 * property with an initializer or a delegate has its value once that has run, and one assigned in an `init`
 * block once the assignment has. A read of a property with a backing field and a plain getter (by its bare name
 * or `this.x`) in an initializer, a delegate or an `init` block before that point is reported.
 *
 * Only code that runs right there is judged: a lambda, a local function or an object is not entered (whether it
 * runs during construction is not known here), and an `init` block's assignments count as soon as one is seen on
 * any branch, so a value given conditionally is never reported. A bare name a constructor parameter of
 * [ctorParams] or a local declares reads that instead, and is left alone.
 *
 * A deferred member `val` assigned by a statement of an `init` block after it may already have its value is
 * added to [reassigned] (Kotlin's VAL_REASSIGNMENT).
 */
internal fun memberReadsBeforeInitialization(
    declarations: List<dev.ide.kotlin.syntax.psi.KtDeclaration>, ctorParams: Set<String>,
    reassigned: MutableList<KtNameReferenceExpression> = ArrayList(),
    /** Whether a lambda is called in place where it is written (a `callsInPlace` contract): its body then runs
     *  during the initialization too. */
    inPlace: (KtLambdaExpression) -> Boolean = { false },
): List<KtNameReferenceExpression> {
    val props = declarations.filterIsInstance<KtProperty>().filter { p ->
        p.name != null && p.receiverTypeReference == null && !p.hasModifier(KtTokens.LATEINIT_KEYWORD) &&
            !p.hasModifier(KtTokens.OPEN_KEYWORD) && !p.hasModifier(KtTokens.ABSTRACT_KEYWORD) &&
            !p.hasModifier(KtTokens.OVERRIDE_KEYWORD) && !p.hasModifier(KtTokens.EXPECT_KEYWORD) &&
            p.getter?.bodyExpression == null && dev.ide.lang.kotlin.hasBackingField(p)
    }
    if (props.isEmpty()) return emptyList()
    val names = props.mapNotNullTo(HashSet()) { it.name }
    // A name declared twice is left alone: which one a read means is the redeclaration error's business.
    val duplicated = props.groupBy { it.name }.filterValues { it.size > 1 }.keys
    val assigned = HashSet<String>()
    val out = ArrayList<KtNameReferenceExpression>()
    val deferredVals = props.filter { !it.isVar && !it.hasInitializer() && !it.hasDelegate() }.mapNotNullTo(HashSet()) { it.name }

    /** The name an assignment's left side writes (`x = …`, `this.x = …`). */
    fun targetRef(lhs: KtExpression?): KtNameReferenceExpression? {
        var x = lhs
        while (x is KtParenthesizedExpression) x = x.expression
        return when (x) {
            is KtNameReferenceExpression -> x
            is KtDotQualifiedExpression -> x.selectorExpression as? KtNameReferenceExpression
            else -> null
        }
    }

    fun memberName(ref: KtNameReferenceExpression, locals: Set<String>, inObject: Boolean = false): String? {
        val name = ref.getReferencedName()
        if (name !in names || name in duplicated) return null
        val parent = ref.parent
        if (parent is KtQualifiedExpression && parent.selectorExpression === ref) {
            // Inside an object expression `this` is the object.
            if (inObject) return null
            val recv = parent.receiverExpression
            return name.takeIf { parent is KtDotQualifiedExpression && recv is dev.ide.kotlin.syntax.psi.KtThisExpression && recv.getLabelName() == null }
        }
        if (parent is KtCallExpression && parent.calleeExpression === ref) return null
        if (parent is KtValueArgumentName || parent is KtCallableReferenceExpression) return null
        if (name in ctorParams || name in locals) return null
        // A local, a loop variable or a catch parameter of that name inside the initializer hides the member.
        val offset = ref.textRange.startOffset
        var node: KtElement? = ref.parent
        while (node != null && node !is dev.ide.kotlin.syntax.psi.KtFile) {
            when (node) {
                // An object expression's own member of that name hides the outer one; any other class ends the walk.
                is KtClassOrObject -> {
                    if (!(node is dev.ide.kotlin.syntax.psi.KtObjectDeclaration && node.isObjectLiteral())) break
                    if (node.declarations.any { (it as? dev.ide.kotlin.syntax.psi.KtNamedDeclaration)?.name == name }) return null
                }
                // A lambda's parameter (or a component of a destructured one) of that name hides the member.
                is dev.ide.kotlin.syntax.psi.KtFunctionLiteral -> if (node.valueParameters.any {
                        it.name == name || it.destructuringDeclaration?.entries?.any { e -> e.name == name } == true
                    } || (name == "it" && node.valueParameters.isEmpty())) return null
                is KtBlockExpression -> if (node.statements.any { it is KtProperty && it.name == name && it.textRange.endOffset <= offset }) return null
                is KtForExpression -> if (node.loopParameter?.name == name) return null
                is dev.ide.kotlin.syntax.psi.KtCatchClause -> if (node.catchParameter?.name == name) return null
            }
            node = node.parent
        }
        return name
    }

    fun target(lhs: KtExpression?, locals: Set<String>): String? = targetRef(lhs)?.let { memberName(it, locals) }

    // K2 judges a read inside an in-place lambda or an object expression for a `val` only: the same read of a
    // `var` compiles (`val some = run { println(state) }; var state = true`).
    val vals = props.filter { !it.isVar }.mapNotNullTo(HashSet()) { it.name }

    fun reads(e: KtElement?, locals: Set<String>, inObject: Boolean = false, deep: Boolean = false) {
        if (e == null) return
        when (e) {
            // A lambda called in place runs here; any other only when something calls it, which is not known.
            is KtLambdaExpression -> {
                if (inPlace(e)) reads(e.bodyExpression, locals, inObject, deep = true)
                return
            }
            // An object expression is constructed here: its property initializers and `init` blocks run now (its
            // functions only when called). One with supertypes has members this cannot list, so it is not entered.
            is KtObjectLiteralExpression -> {
                val obj = e.objectDeclaration ?: return
                if (obj.superTypeListEntries.isNotEmpty()) return
                for (d in obj.declarations) when (d) {
                    is KtProperty -> { reads(d.initializer, locals, true, deep = true); reads(d.delegate?.expression, locals, true, deep = true) }
                    is dev.ide.kotlin.syntax.psi.KtClassInitializer -> reads(d.body, locals, true, deep = true)
                    else -> {}
                }
                return
            }
            is KtNamedFunction, is KtClassOrObject, is dev.ide.kotlin.syntax.psi.KtTypeReference -> return
            is KtNameReferenceExpression -> {
                memberName(e, locals, inObject)?.let { if (it !in assigned && (!deep || it in vals)) out += e }
                return
            }
            // The left side of `x = …` is written, not read.
            is KtBinaryExpression -> if (e.operationToken == KtTokens.EQ && target(e.left, locals) != null) {
                reads(e.right, locals, inObject, deep)
                return
            }
        }
        var c = e.firstChild
        while (c != null) { reads(c, locals, inObject, deep); c = c.nextSibling }
    }



    /** One statement of an `init` block: its reads, then what it assigns (on any branch). */
    fun statement(st: KtElement, locals: Set<String>) {
        if (st is KtBinaryExpression && st.operationToken == KtTokens.EQ) {
            val t = target(st.left, locals)
            if (t != null) {
                reads(st.right, locals)
                // A `val` given its value a second time (`val i: Int; init { i = 1 }; init { i = 2 }`).
                if (t in assigned && t in deferredVals) targetRef(st.left)?.let { reassigned += it }
                assigned += t
                return
            }
        }
        reads(st, locals)
        fun rec(p: KtElement) {
            if ((p is KtLambdaExpression && !inPlace(p)) || p is KtNamedFunction || p is KtClassOrObject) return
            if (p is KtBinaryExpression && p.operationToken == KtTokens.EQ) target(p.left, locals)?.let { assigned += it }
            var c = p.firstChild
            while (c != null) { rec(c); c = c.nextSibling }
        }
        rec(st)
    }

    for (d in declarations) {
        when (d) {
            is KtProperty -> {
                reads(d.initializer, emptySet())
                reads(d.delegate?.expression, emptySet())
                val n = d.name
                if (n != null && (d.hasInitializer() || d.hasDelegate())) assigned += n
            }
            is dev.ide.kotlin.syntax.psi.KtClassInitializer -> {
                val locals = HashSet<String>()
                for (st in d.body?.statements.orEmpty()) {
                    statement(st, locals)
                    if (st is KtProperty) st.name?.let { locals += it }
                }
            }
            else -> {}
        }
    }
    return out
}
