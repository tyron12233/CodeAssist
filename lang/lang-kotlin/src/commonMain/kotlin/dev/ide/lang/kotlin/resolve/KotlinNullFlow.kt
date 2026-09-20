package dev.ide.lang.kotlin.resolve

import dev.ide.kotlin.syntax.psi.KtBinaryExpression
import dev.ide.kotlin.syntax.psi.KtBlockExpression
import dev.ide.kotlin.syntax.psi.KtCallExpression
import dev.ide.kotlin.syntax.psi.KtCatchClause
import dev.ide.kotlin.syntax.psi.KtClassOrObject
import dev.ide.kotlin.syntax.psi.KtConstantExpression
import dev.ide.kotlin.syntax.psi.KtDotQualifiedExpression
import dev.ide.kotlin.syntax.psi.KtElement
import dev.ide.kotlin.syntax.psi.KtExpression
import dev.ide.kotlin.syntax.psi.KtForExpression
import dev.ide.kotlin.syntax.psi.KtFunction
import dev.ide.kotlin.syntax.psi.KtIfExpression
import dev.ide.kotlin.syntax.psi.KtIsExpression
import dev.ide.kotlin.syntax.psi.KtNameReferenceExpression
import dev.ide.kotlin.syntax.psi.KtParameter
import dev.ide.kotlin.syntax.psi.KtParenthesizedExpression
import dev.ide.kotlin.syntax.psi.KtPostfixExpression
import dev.ide.kotlin.syntax.psi.KtPrefixExpression
import dev.ide.kotlin.syntax.psi.KtProperty
import dev.ide.kotlin.syntax.psi.KtSafeQualifiedExpression
import dev.ide.kotlin.syntax.psi.KtThisExpression
import dev.ide.kotlin.syntax.psi.KtTokens
import dev.ide.kotlin.syntax.psi.KtWhileExpression

/**
 * Flow-sensitive NULLABILITY narrowing — the null-check half of smart casting, layered over the same
 * position-based (PSI-ancestor) analysis [smartCastTypeAt] already uses for `is`/`when` narrowing. Answers
 * "does a null guard on the path prove this simple name is non-null here?" and pairs it with the Kotlin
 * smart-cast STABILITY rules so a caller only ever concludes non-null for a value that genuinely cannot change.
 *
 * ## Why position-based (not a full data-flow fixpoint) is sound here
 * A smart cast is valid only on a *stable* value — one whose value can't change between the guard and the use
 * (Kotlin spec, "smart cast sink stability"). We restrict smart-cast conclusions to the subset of stable values
 * that are also **immutable**: a `val` local variable (no delegate/custom getter — locals can't have getters)
 * and a parameter (function/lambda/for/catch — all immutable). For an immutable value, reassignment is
 * impossible, so a guard that dominates the use position (an enclosing `if (x != null)` then-branch, a preceding
 * `x ?: return`, …) proves non-null at the use with NO need to reason about intervening writes — exactly what the
 * cheap ancestor walk computes. The cases that would demand a real CFG + fixpoint are precisely the ones the
 * stability rules exclude here: a `var` (whose effective-immutability depends on reassignment/closure-capture
 * ordering), a delegated or custom-getter property, and cross-module `open` members. Those we conservatively
 * treat as NOT stable ([stableForSmartCast] returns false), so we never draw an unsound conclusion — a missed
 * smart cast under-reports (safe), we never over-report.
 *
 * ## The stability rules we encode (Kotlin language spec)
 * Stable & smart-castable HERE (we return non-null when a guard proves it):
 *  - a `val` local variable without a delegate;
 *  - a function / lambda / `for` / `catch` parameter (immutable).
 * NOT stable (we back off — return false, never smart-cast):
 *  - a `var` local (effective-immutability needs reassignment/closure analysis — a future fixpoint);
 *  - a delegated local (`val x by …` — the delegate's getValue may vary);
 *  - any member / classifier-scope property, and any qualified receiver (`a.b`) — module/open/custom-getter
 *    stability isn't decidable soundly in the parse-only model (a conservative miss; the common
 *    same-file `private val x: T?` pattern is a candidate refinement);
 *  - an unresolved name.
 */

/** Whether the simple name [name] used at [offset] is smart-cast to a non-null value — honouring the stability
 *  rules above. Combines a null-guard ([nullGuardedNonNullAt]) and an `is T` narrowing (both imply non-null). */
internal fun KotlinResolver.smartCastNonNull(ref: KtNameReferenceExpression): Boolean {
    val name = ref.getReferencedName()
    val offset = ref.textRange.startOffset
    if (stableForSmartCast(name, offset, ref)) {
        // An immutable stable sink (val local / parameter / final val member) — position-based narrowing is sound
        // (no reassignment can invalidate it) and carries into nested lambdas.
        if (nullGuardedNonNullAt(name, offset)) return true
        val narrowed =
            smartCastTypeAt(name, offset) // an `is T` narrowing yields a non-null classifier
        return narrowed != null && !narrowed.nullable
    }
    // Otherwise: an effectively-immutable local `var` may still be smart-cast — that needs reassignment-aware
    // control-flow (a guard is invalidated by a later `x = …`), handled by the CFG var-nullability pass.
    return varSmartCastNonNull(ref)
}

/** Whether [name] used at [offset] binds to a value STABLE + immutable for smart-casting (see the file header):
 *  a `val` local (no delegate), a parameter, or a same-file final `val` member with a backing field read through
 *  the enclosing instance. Resolves to the NEAREST binding (an inner shadow wins) and reports that binding's
 *  stability; a `var`/delegated local, an `open`/custom-getter/qualified-receiver member, or an unresolved name
 *  is not stable. */
internal fun KotlinResolver.stableForSmartCast(
    name: String, offset: Int, from: KtElement
): Boolean {
    val bareOrThis = isBareOrThisReceiver(from)
    var node: KtElement? = from.parent
    while (node != null) {
        when (node) {
            is KtBlockExpression -> {
                val local = node.statements.firstOrNull {
                    it is KtProperty && it.name == name && it.textRange.endOffset <= offset
                } as? KtProperty
                if (local != null) return !local.isVar && !local.hasDelegate()
            }

            is KtFunction -> node.valueParameters.firstOrNull { it.name == name }?.let {
                // A plain value parameter is immutable → stable; a primary-constructor `val`/`var` parameter is a
                // member property, handled in the KtClassOrObject case below with the member-stability rules.
                if (!it.hasValOrVar()) return true
            }

            is KtForExpression -> node.loopParameter?.takeIf { it.name == name }
                ?.let { return true }

            is KtCatchClause -> node.catchParameter?.takeIf { it.name == name }?.let { return true }
            // A member read (bare or `this.name`): a `val` member of THIS file's enclosing class with a backing
            // field, not overridable, is stable (Kotlin spec: private/internal/same-module + not open + no custom
            // getter — same-file ⊂ same-module, and final ⇒ can't be overridden with a getter). `foo.name` on any
            // other receiver, or a `var`/open/custom-getter member, is not stable. Continue outward when the name
            // isn't a member here (a nested class may read an outer member).
            is KtClassOrObject -> if (bareOrThis) when (stableValMemberKind(node, name)) {
                MemberStability.STABLE -> return true
                MemberStability.UNSTABLE -> return false
                MemberStability.ABSENT -> {} // look in an enclosing class
            }
        }
        node = node.parent
    }
    return false
}

private enum class MemberStability { STABLE, UNSTABLE, ABSENT }

/** Whether a `val` member [name] of [cls] is a stable smart-cast sink: a property with a backing field (no
 *  custom getter, no delegate) that is not `var`/`open`/`abstract`/`override`, or a final `val` primary-
 *  constructor property. [MemberStability.ABSENT] when [cls] declares no such name (search continues outward). */
private fun stableValMemberKind(cls: KtClassOrObject, name: String): MemberStability {
    val prop = cls.declarations.firstOrNull { it is KtProperty && it.name == name } as? KtProperty
    if (prop != null) {
        val stable =
            !prop.isVar && prop.getter == null && !prop.hasDelegate() && !prop.hasModifier(KtTokens.OPEN_KEYWORD) && !prop.hasModifier(
                KtTokens.ABSTRACT_KEYWORD
            ) && !prop.hasModifier(KtTokens.OVERRIDE_KEYWORD)
        return if (stable) MemberStability.STABLE else MemberStability.UNSTABLE
    }
    val ctorParam =
        cls.primaryConstructor?.valueParameters?.firstOrNull { it.name == name && it.hasValOrVar() }
    if (ctorParam != null) {
        val stable =
            !ctorParam.isMutable && !ctorParam.hasModifier(KtTokens.OPEN_KEYWORD) && !ctorParam.hasModifier(
                KtTokens.OVERRIDE_KEYWORD
            )
        return if (stable) MemberStability.STABLE else MemberStability.UNSTABLE
    }
    return MemberStability.ABSENT
}

/** Whether [ref] reads its name through a stable implicit receiver — a bare name, or `this.name` (the enclosing
 *  instance) — as opposed to `someOtherExpr.name` (whose receiver stability we don't analyse). */
private fun isBareOrThisReceiver(ref: KtElement): Boolean {
    val parent = ref.parent
    if (parent is KtDotQualifiedExpression && parent.selectorExpression === ref) {
        return stripParens(parent.receiverExpression) is KtThisExpression
    }
    return true // a bare name, or the name is itself a receiver (`name.foo`) → a bare read of `name`
}

/** Whether a null guard in flow scope proves [name] is non-null at [offset]. Mirrors [smartCastTypeAt]'s
 *  ancestor walk: the then-branch of `if (name != null)`, the else of `if (name == null)`, the `&&`/`||`
 *  short-circuit RHS, a `while (name != null)` body, and a preceding early-exit guard in the enclosing block
 *  (`if (name == null) return`, `name ?: return`, `name!!`, `requireNotNull(name)`). SOUND ONLY for a stable
 *  immutable value — [smartCastNonNull] gates on [stableForSmartCast], so reassignment can't invalidate it. */
internal fun KotlinResolver.nullGuardedNonNullAt(name: String, offset: Int): Boolean =
    guardedNonNullAt(offset) { (stripParens(it) as? KtNameReferenceExpression)?.getReferencedName() == name }

/** As [nullGuardedNonNullAt] but matches a QUALIFIED access path by its exact text (`b.s`, `this.x`). Used to
 *  suppress a false unsafe-call on a guarded member dereference (`if (b.s != null) b.s.length`). Since it only
 *  ever SUPPRESSES an error, text-matching is sound: at worst a reassignment inside the path under-suppresses. */
internal fun KotlinResolver.pathGuardedNonNullAt(path: String, offset: Int): Boolean =
    guardedNonNullAt(offset) { stripParens(it)?.text == path }

/** The shared ancestor walk for [nullGuardedNonNullAt] / [pathGuardedNonNullAt], parameterized by a [matches]
 *  predicate deciding whether an operand expression refers to the guarded value (a name, or an access path). */
private fun KotlinResolver.guardedNonNullAt(
    offset: Int, matches: (KtExpression?) -> Boolean
): Boolean {
    var child: KtElement? = null
    var node: KtElement? = elementAt(offset)
    while (node != null) {
        when (node) {
            is KtIfExpression -> {
                if (node.then?.textRange?.contains(offset) == true && condGuarantees(
                        node.condition, true, matches
                    )
                ) return true
                if (node.`else`?.textRange?.contains(offset) == true && condGuarantees(
                        node.condition, false, matches
                    )
                ) return true
            }

            is KtWhileExpression -> if (node.body?.textRange?.contains(offset) == true && condGuarantees(
                    node.condition, true, matches
                )
            ) return true

            is KtBinaryExpression -> if (node.right?.textRange?.contains(offset) == true) when (node.operationToken) {
                KtTokens.ANDAND -> if (condGuarantees(node.left, true, matches)) return true
                KtTokens.OROR -> if (condGuarantees(node.left, false, matches)) return true
                else -> {}
            }

            is KtBlockExpression -> if (earlyExitGuarded(node, child, matches)) return true
        }
        child = node
        node = node.parent
    }
    return false
}

/** Whether [cond], evaluating to [whenTrue], guarantees the matched value is non-null: `v != null` (true side) /
 *  `v == null` (false side), an `is`/negation, and `&&` (true side) / `||` (false side) conjunctions. */
private fun KotlinResolver.condGuarantees(
    cond: KtExpression?, whenTrue: Boolean, matches: (KtExpression?) -> Boolean
): Boolean = when (val c = unwrapParens(cond)) {
    is KtBinaryExpression -> when (c.operationToken) {
        KtTokens.EXCLEQ -> whenTrue && isNullCmp(c, matches)   // v != null, true side
        KtTokens.EQEQ -> !whenTrue && isNullCmp(c, matches)    // v == null, false side
        KtTokens.ANDAND -> whenTrue && (condGuarantees(c.left, true, matches) || condGuarantees(
            c.right, true, matches
        ))

        KtTokens.OROR -> !whenTrue && (condGuarantees(c.left, false, matches) || condGuarantees(
            c.right, false, matches
        ))

        else -> false
    }
    // `v is T` (a non-nullable target) implies non-null on the true side (`!is` flips it).
    is KtIsExpression -> matches(c.leftHandSide) && whenTrue != c.isNegated && isNonNullIsTarget(
        c.typeReference?.text
    )

    is KtPrefixExpression -> if (c.operationToken == KtTokens.EXCL) condGuarantees(
        c.baseExpression, !whenTrue, matches
    ) else false

    // `v.isNullOrBlank()` / `v.isNullOrEmpty()` returning FALSE implies `v != null`, which is what their
    // `returns(false) implies (this != null)` contract says. Those two are the idiomatic null guard for a
    // nullable string or collection, and without them the most ordinary shape there is --
    //
    //     if (doc.isNullOrBlank()) return
    //     doc.lineSequence()          // <- "only safe (?.) calls are allowed on a nullable receiver"
    //
    // reported the line AFTER the guard. Matched by name on a qualified call, the same way the preconditions
    // below are: reading the real contract needs the declaration, and these two are stdlib names whose meaning
    // is fixed. A project function that shadows one of them would be believed here, which is the same trade
    // `requireNotNull`/`check` already make.
    is KtDotQualifiedExpression -> !whenTrue && isNullOrEmptyGuard(c, matches)

    is KtSafeQualifiedExpression -> false

    else -> false
}

/** `v.isNullOrBlank()` / `v.isNullOrEmpty()` on the matched value — false implies non-null (their contract). */
private fun isNullOrEmptyGuard(c: KtDotQualifiedExpression, matches: (KtExpression?) -> Boolean): Boolean {
    val call = c.selectorExpression as? KtCallExpression ?: return false
    if (call.valueArguments.isNotEmpty()) return false
    val name = (call.calleeExpression as? KtNameReferenceExpression)?.getReferencedName()
    if (name != "isNullOrBlank" && name != "isNullOrEmpty") return false
    return matches(c.receiverExpression)
}

/** Whether [binary] is `v == null` / `v != null` (either operand order) for the matched value. */
private fun isNullCmp(binary: KtBinaryExpression, matches: (KtExpression?) -> Boolean): Boolean =
    (matches(binary.left) && isNullLit(stripParens(binary.right))) || (matches(binary.right) && isNullLit(
        stripParens(binary.left)
    ))

/** An early-exit guard preceding [fromChild] in [block] that leaves the matched value non-null for the rest of
 *  the block: `if (v == null) <jump>` / `if (v != null) {} else <jump>`, `v ?: <jump>`, `v!!`, `requireNotNull(v)`. */
private fun KotlinResolver.earlyExitGuarded(
    block: KtBlockExpression, fromChild: KtElement?, matches: (KtExpression?) -> Boolean
): Boolean {
    for (st in block.statements) {
        if (st === fromChild) break
        when {
            st is KtIfExpression -> {
                val thenJumps = branchAlwaysJumps(st.then)
                val elseJumps = st.`else` != null && branchAlwaysJumps(st.`else`)
                if (thenJumps && !elseJumps && condGuarantees(
                        st.condition, false, matches
                    )
                ) return true
                if (elseJumps && !thenJumps && condGuarantees(
                        st.condition, true, matches
                    )
                ) return true
            }

            isElvisJump(st, matches) -> return true
            // The same guard written as a local's initializer: `val t = x?.y ?: return`. Opening a function
            // that way is the idiom this module uses to take a nullable parameter and get on with it.
            st is KtProperty && st.initializer?.let { isElvisJump(it, matches) } == true -> return true
            isNotNullAssertStatement(st, matches) -> return true
            isNotNullPrecondition(st, matches) -> return true
        }
    }
    return false
}

/** `v ?: <jump>` — an elvis whose left is the matched value and whose right unconditionally transfers out. */
private fun KotlinResolver.isElvisJump(
    st: KtExpression, matches: (KtExpression?) -> Boolean
): Boolean {
    val e = stripParens(st) as? KtBinaryExpression ?: return false
    return e.operationToken == KtTokens.ELVIS && safeChainRootMatches(e.left, matches) &&
        branchAlwaysJumps(e.right)
}

/**
 * Whether [expr] is the matched value, or a chain of SAFE calls rooted at it (`x?.y?.z`).
 *
 * `x?.y?.z ?: return` proves `x` non-null past it for the same reason `x ?: return` does: a null `x` makes
 * the whole chain null, so the jump would have been taken. `val text = leaf?.containingFile?.text ?: return`
 * opens several functions in this module and left every later use of `leaf` reported as a nullable receiver.
 *
 * Only the ROOT is claimed. The intermediate links are non-null too, but they are access PATHS rather than
 * names, matched by text elsewhere with a `.` where the chain writes `?.`, so claiming them here would not
 * match anyway.
 */
private fun safeChainRootMatches(expr: KtElement?, matches: (KtExpression?) -> Boolean): Boolean {
    var cur: KtElement? = stripParens(expr)
    while (cur != null) {
        if (matches(cur as? KtExpression)) return true
        cur = (cur as? KtSafeQualifiedExpression)?.let { stripParens(it.receiverExpression) }
    }
    return false
}

/** `v!!` used at the top of statement [st] (`v!!`, `v!!.foo`, `v!!()`), which throws when null. */
private fun isNotNullAssertStatement(
    st: KtExpression, matches: (KtExpression?) -> Boolean
): Boolean {
    val postfix = when (val e = stripParens(st)) {
        is KtPostfixExpression -> e
        is KtDotQualifiedExpression -> stripParens(e.receiverExpression) as? KtPostfixExpression
        else -> null
    } ?: return false
    return postfix.operationToken == KtTokens.EXCLEXCL && matches(postfix.baseExpression)
}

/** A stdlib precondition that makes the accessed value non-null past the call: `requireNotNull(v)` /
 *  `checkNotNull(v)` (throws on a null VALUE), or `require(cond)` / `check(cond)` whose boolean condition
 *  guarantees it (`require(v != null)`, `check(a != null && b != null)`) — the `returns() implies (…)`
 *  contract smart-casts after the call. */
private fun KotlinResolver.isNotNullPrecondition(st: KtExpression, matches: (KtExpression?) -> Boolean): Boolean {
    val call = stripParens(st) as? KtCallExpression ?: return false
    val callee = (call.calleeExpression as? KtNameReferenceExpression)?.getReferencedName() ?: return false
    val arg = call.valueArguments.singleOrNull()?.getArgumentExpression()
    return when (callee) {
        "requireNotNull", "checkNotNull" -> matches(arg)
        "require", "check" -> arg != null && condGuarantees(arg, true, matches)
        else -> false
    }
}

/** The classifier target of an `is` check is non-null when its written text carries no trailing `?`. */
private fun isNonNullIsTarget(typeText: String?): Boolean =
    typeText != null && !typeText.trim().endsWith("?")

private fun isNullLit(e: KtElement?): Boolean = e is KtConstantExpression && e.text == "null"

/** Unwrap parentheses (a local mirror of the resolver's `unwrapParens`, usable on any [KtElement]). */
private fun stripParens(e: KtElement?): KtElement? {
    var cur = e
    while (cur is KtParenthesizedExpression) cur = cur.expression
    return cur
}
