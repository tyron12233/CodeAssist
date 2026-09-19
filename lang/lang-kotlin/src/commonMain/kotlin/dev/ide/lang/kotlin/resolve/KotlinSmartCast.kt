package dev.ide.lang.kotlin.resolve

import dev.ide.kotlin.syntax.psi.KtBinaryExpression
import dev.ide.kotlin.syntax.psi.KtBinaryExpressionWithTypeRHS
import dev.ide.kotlin.syntax.psi.KtBlockExpression
import dev.ide.kotlin.syntax.psi.KtBreakExpression
import dev.ide.kotlin.syntax.psi.KtContinueExpression
import dev.ide.kotlin.syntax.psi.KtElement
import dev.ide.kotlin.syntax.psi.KtExpression
import dev.ide.kotlin.syntax.psi.KtIfExpression
import dev.ide.kotlin.syntax.psi.KtIsExpression
import dev.ide.kotlin.syntax.psi.KtNameReferenceExpression
import dev.ide.kotlin.syntax.psi.KtReturnExpression
import dev.ide.kotlin.syntax.psi.KtThisExpression
import dev.ide.kotlin.syntax.psi.KtThrowExpression
import dev.ide.kotlin.syntax.psi.KtTokens
import dev.ide.kotlin.syntax.psi.KtTypeReference
import dev.ide.kotlin.syntax.psi.KtWhenConditionIsPattern
import dev.ide.kotlin.syntax.psi.KtWhenConditionWithExpression
import dev.ide.kotlin.syntax.psi.KtWhenEntry
import dev.ide.kotlin.syntax.psi.KtWhenExpression
import dev.ide.kotlin.syntax.psi.KtWhileExpression
import dev.ide.lang.kotlin.symbols.KotlinType

/** Flow-sensitive smart-cast narrowing: the `name -> narrowed type` scopes the lowerer pushes and the static narrowings inferred from `is`/`when`/early-exit conditions. */

internal fun KotlinResolver.narrowedType(name: String): KotlinType? {
    for (i in narrowings.indices.reversed()) narrowings[i][name]?.let { return it }
    return null
}

//
// The lowerer's [narrowings] stack above is push/pop-driven and only the interpreter uses it. The EDITOR
// (completion + diagnostics) never pushes onto it, so it derives smart casts the other way: PURELY from a
// name reference's POSITION in the (immutable) PSI, i.e. which `is` checks enclose it. Since that is a
// function of the expression alone, [inferType]'s cache holds it correctly. [typeOfName] consults it (gated
// to when the narrowing stack is empty, so the interpreter's flow-narrowing stays authoritative there).

/**
 * The smart-cast type of a simple-name reference [name] used at [offset]: the type an `is` check in flow
 * scope narrows it to. Covers the `if (x is T)` then-branch (and the `else` of an `if (x !is T)`), the
 * short-circuit RHS of `x is T && …` / `x !is T || …`, a `when (x) { is T -> … }` branch, a `while (x is T)`
 * body, and the statements after an `if (x !is T) return`/`throw`/`break`/`continue` early-exit guard. Null
 * when no narrowing is in effect. [name] is a declared name, or [THIS_SUBJECT] for a check written against
 * the implicit receiver (`when (this) { is T -> … }`). Conservative like the lowerer: only a simple-name or
 * `this` subject narrows, and only to a classifier that resolves (generic args erased, made non-null, since `is T` implies non-null `T`); a
 * parameterized/unresolved cast target degrades to null. Soundness-wise this can only ADD members a value
 * has after the user-written check (matching Kotlin), so a missed narrowing under-reports (never a false
 * "unresolved"), and a spurious one only fails to flag an error Kotlin would, never the reverse.
 */
internal fun KotlinResolver.smartCastTypeAt(name: String, offset: Int): KotlinType? {
    var child: KtElement? = null
    var node: KtElement? = elementAt(offset)
    while (node != null) {
        narrowingAtNode(node, child, offset, name)?.let { return it }
        child = node
        node = node.parent
    }
    return null
}

/**
 * The narrowing [node] ALONE imposes on [name] at [offset], where [child] is the node on the path from the
 * use site up to it (needed to tell which branch of a `when`, or which statement of a block, the use site is
 * in). Null when this particular ancestor narrows nothing.
 *
 * Split out of [smartCastTypeAt]'s loop so the implicit-receiver walk can ask the same question of each
 * ancestor as it climbs, instead of running a second traversal per receiver.
 */
internal fun KotlinResolver.narrowingAtNode(
    node: KtElement,
    child: KtElement?,
    offset: Int,
    name: String,
): KotlinType? {
    when (node) {
        // The then/else of an `if`, the body of a `while`, are each wrapped in a control-structure
        // container node, so the use site is matched by RANGE, not by child identity against the
        // (unwrapped) branch.
        is KtIfExpression -> {
            if (node.then?.textRange?.contains(offset) == true) conditionNarrowing(
                node.condition,
                name,
                whenTrue = true
            )?.let { return it }
            if (node.`else`?.textRange?.contains(offset) == true) conditionNarrowing(
                node.condition,
                name,
                whenTrue = false
            )?.let { return it }
        }

        is KtWhileExpression ->
            if (node.body?.textRange?.contains(offset) == true) conditionNarrowing(
                node.condition,
                name,
                whenTrue = true
            )?.let { return it }
        // The short-circuit RHS of `&&`/`||` sees the LHS's narrowing (`x is T && x.member`,
        // `x !is T || x.member`). Only the RHS; the LHS itself runs unnarrowed (disjoint ranges).
        is KtBinaryExpression -> if (node.right?.textRange?.contains(offset) == true) when (node.operationToken) {
            KtTokens.ANDAND -> conditionNarrowing(
                node.left,
                name,
                whenTrue = true
            )?.let { return it }

            KtTokens.OROR -> conditionNarrowing(
                node.left,
                name,
                whenTrue = false
            )?.let { return it }

            else -> {}
        }

        is KtWhenExpression -> whenSubjectNarrowing(node, child, name)?.let { return it }
        is KtBlockExpression -> earlyExitNarrowing(node, child, name)?.let { return it }
    }
    return null
}

/**
 * The key an `is` check written against `this` is recorded under. `this` is a keyword, so no declaration can
 * carry that name and the key cannot collide with a real variable's.
 *
 * Only an UNLABELED `this` takes it: `this@Outer is T` names one specific receiver in the chain, and the
 * chain is matched by position rather than by label, so narrowing on a label would need the walk to know
 * which entry the label picked. Leaving it unnarrowed only ever under-reports.
 */
internal const val THIS_SUBJECT = "this"

/** The smart-cast key an `is` check's subject stands for: its simple name, or [THIS_SUBJECT] for a bare
 *  `this`. Null for everything else (a call, a qualified chain, a labeled `this`), which never narrows. */
internal fun KotlinResolver.smartCastSubjectKey(expr: KtExpression?): String? =
    when (val e = unwrapParens(expr)) {
        is KtNameReferenceExpression -> e.getReferencedName()
        is KtThisExpression -> if (e.getLabelName() == null) THIS_SUBJECT else null
        else -> null
    }

/** The narrowing a condition imposes on [name] when it evaluates to [whenTrue]: from `name is T` (true side)
 *  / `name !is T` (false side), conjoined through `&&` on the true side and `||` on the false side. Null when
 *  the condition doesn't narrow [name] or the target won't resolve. Mirrors the lowerer's `conditionNarrowings`,
 *  keyed to one name. */
internal fun KotlinResolver.conditionNarrowing(
    cond: KtExpression?,
    name: String,
    whenTrue: Boolean
): KotlinType? =
    when (val c = unwrapParens(cond)) {
        is KtIsExpression -> {
            val lhs = smartCastSubjectKey(c.leftHandSide)
            if (lhs == name && whenTrue != c.isNegated) typeFromIsTarget(c.typeReference) else null
        }

        is KtBinaryExpression -> when (c.operationToken) {
            KtTokens.ANDAND -> if (whenTrue) conditionNarrowing(c.left, name, true)
                ?: conditionNarrowing(c.right, name, true) else null

            KtTokens.OROR -> if (!whenTrue) conditionNarrowing(c.left, name, false)
                ?: conditionNarrowing(c.right, name, false) else null

            else -> null
        }

        else -> null
    }

/** `when (subject) { is T -> ‹here› }` (or a subject `val`) narrows a simple-name subject to `T` inside a
 *  positive single-`is` branch; a subjectless `when { name is T -> … }` narrows via the branch condition.
 *  [fromChild] is the `when`'s child on the path; only a branch entry narrows (not the subject/`else`). */
internal fun KotlinResolver.whenSubjectNarrowing(
    whenExpr: KtWhenExpression,
    fromChild: KtElement?,
    name: String
): KotlinType? {
    val entry = fromChild as? KtWhenEntry ?: return null
    if (entry.isElse) return null
    val subjectName = whenSubjectName(whenExpr)
    if (subjectName != null) {
        if (subjectName != name) return null
        // Only a single positive `is T` narrows; a comma branch (`is A, is B`) doesn't smart-cast.
        val pattern = entry.conditions.singleOrNull() as? KtWhenConditionIsPattern ?: return null
        return if (pattern.isNegated) null else typeFromIsTarget(pattern.typeReference)
    }
    // Subjectless `when { name is T -> … }`: the branch condition is a boolean expression on names.
    val condExpr = (entry.conditions.singleOrNull() as? KtWhenConditionWithExpression)?.expression
        ?: return null
    return conditionNarrowing(condExpr, name, whenTrue = true)
}

/** The key a `when` narrows on: its subject `val` (`when (val y = …)` → `y`), a simple-name subject
 *  (`when (x)` → `x`), or [THIS_SUBJECT] for `when (this)`; null for a computed/absent subject. */
internal fun KotlinResolver.whenSubjectName(whenExpr: KtWhenExpression): String? {
    whenExpr.subjectVariable?.name?.let { return it }
    return smartCastSubjectKey(whenExpr.subjectExpression)
}

/**
 * The narrowing a preceding STATEMENT in [block] leaves in effect for [name]. [fromChild] is the statement on
 * the path to the use site; only statements before it count, and the last applicable one wins.
 *
 * Two shapes do it:
 *  - an early-exit guard, `if (name !is T) return`, which makes `name` a `T` for the rest of the block;
 *  - a bare cast statement, `name as T`, which does the same because the cast throws when it does not hold.
 *    That is how a checker lambda opens (`KotlinChecker(KtProperty::class) { psi -> psi as KtProperty; … }`)
 *    and without it every later use of `psi` was still the parameter's declared type: 20+ of this module's
 *    `kt.typeMismatch` hits were one file's dispatch table saying `KtElement but KtProperty was expected`.
 *    `as?` is excluded -- it yields null instead of throwing, so it guarantees nothing.
 */
internal fun KotlinResolver.earlyExitNarrowing(
    block: KtBlockExpression,
    fromChild: KtElement?,
    name: String
): KotlinType? {
    var result: KotlinType? = null
    for (st in block.statements) {
        if (st === fromChild) break
        if (st is KtBinaryExpressionWithTypeRHS) {
            if (st.operationReference.getReferencedNameElementType() == KtTokens.AS_KEYWORD &&
                smartCastSubjectKey(st.left) == name
            ) {
                typeFromIsTarget(st.right)?.let { result = it }
            }
            continue
        }
        val guard = st as? KtIfExpression ?: continue
        if (guard.`else` != null) continue                // a fall-through `else` isn't an early exit
        if (!branchAlwaysJumps(guard.then)) continue       // the then must transfer control out
        // After `if (cond) <jump>`, the rest of the block holds cond == false.
        conditionNarrowing(guard.condition, name, whenTrue = false)?.let { result = it }
    }
    return result
}

/** Whether [branch] unconditionally transfers control out of the enclosing block (so code after the guard
 *  is reached only when the guard's condition was false): a `return`/`throw`/`break`/`continue`, or a block
 *  whose last statement does. */
internal fun KotlinResolver.branchAlwaysJumps(branch: KtExpression?): Boolean =
    when (val b = branch) {
        is KtReturnExpression, is KtThrowExpression, is KtBreakExpression, is KtContinueExpression -> true
        is KtBlockExpression -> branchAlwaysJumps(b.statements.lastOrNull())
        else -> false
    }

/** The classifier a smart-cast `is T` narrows to: generic args erased and made non-null. Null when [ref] is
 *  absent or doesn't resolve. Takes the type REFERENCE, not its text, so the enclosing class is available and
 *  a nested `is T` target resolves by simple name. */
internal fun KotlinResolver.typeFromIsTarget(ref: KtTypeReference?): KotlinType? {
    val text = ref?.text?.substringBefore('<')?.removeSuffix("?")?.trim()?.takeIf { it.isNotEmpty() }
        ?: return null
    return runCatching { service.typeFromText(text, fileContext, enclosingClassFqnOf(ref)) }.getOrNull()
}
