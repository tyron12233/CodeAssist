package dev.ide.lang.kotlin.resolve

import dev.ide.kotlin.classfile.KotlinContractCodec
import dev.ide.kotlin.classfile.KotlinContractConstant
import dev.ide.kotlin.classfile.KotlinContractEffect
import dev.ide.kotlin.classfile.KotlinContractExpression
import dev.ide.kotlin.syntax.psi.KtAnnotatedExpression
import dev.ide.kotlin.syntax.psi.KtArrayAccessExpression
import dev.ide.kotlin.syntax.psi.KtBinaryExpression
import dev.ide.kotlin.syntax.psi.KtBinaryExpressionWithTypeRHS
import dev.ide.kotlin.syntax.psi.KtBlockExpression
import dev.ide.kotlin.syntax.psi.KtBreakExpression
import dev.ide.kotlin.syntax.psi.KtCallExpression
import dev.ide.kotlin.syntax.psi.KtCatchClause
import dev.ide.kotlin.syntax.psi.KtClassOrObject
import dev.ide.kotlin.syntax.psi.KtConstantExpression
import dev.ide.kotlin.syntax.psi.KtConstructorDelegationCall
import dev.ide.kotlin.syntax.psi.KtContinueExpression
import dev.ide.kotlin.syntax.psi.KtDoWhileExpression
import dev.ide.kotlin.syntax.psi.KtDotQualifiedExpression
import dev.ide.kotlin.syntax.psi.KtElement
import dev.ide.kotlin.syntax.psi.KtExpression
import dev.ide.kotlin.syntax.psi.KtExpressionWithLabel
import dev.ide.kotlin.syntax.psi.KtForExpression
import dev.ide.kotlin.syntax.psi.KtFunction
import dev.ide.kotlin.syntax.psi.KtFunctionLiteral
import dev.ide.kotlin.syntax.psi.KtIfExpression
import dev.ide.kotlin.syntax.psi.KtIsExpression
import dev.ide.kotlin.syntax.psi.KtLabeledExpression
import dev.ide.kotlin.syntax.psi.KtLoopExpression
import dev.ide.kotlin.syntax.psi.KtNameReferenceExpression
import dev.ide.kotlin.syntax.psi.KtNamedFunction
import dev.ide.kotlin.syntax.psi.KtFile
import dev.ide.kotlin.syntax.psi.KtClassBody
import dev.ide.kotlin.syntax.psi.KtParameter
import dev.ide.kotlin.syntax.psi.KtParenthesizedExpression
import dev.ide.kotlin.syntax.psi.KtPostfixExpression
import dev.ide.kotlin.syntax.psi.KtPrefixExpression
import dev.ide.kotlin.syntax.psi.KtProperty
import dev.ide.kotlin.syntax.psi.KtReturnExpression
import dev.ide.kotlin.syntax.psi.KtSafeQualifiedExpression
import dev.ide.kotlin.syntax.psi.KtSecondaryConstructor
import dev.ide.kotlin.syntax.psi.KtStringTemplateEntryWithExpression
import dev.ide.kotlin.syntax.psi.KtStringTemplateExpression
import dev.ide.kotlin.syntax.psi.KtThisExpression
import dev.ide.kotlin.syntax.psi.KtThrowExpression
import dev.ide.kotlin.syntax.psi.KtTokens
import dev.ide.kotlin.syntax.psi.KtTryExpression
import dev.ide.kotlin.syntax.psi.KtDeclarationWithBody
import dev.ide.kotlin.syntax.psi.KtClass
import dev.ide.kotlin.syntax.psi.KtQualifiedExpression
import dev.ide.kotlin.syntax.psi.getStrictParentOfType
import dev.ide.kotlin.syntax.psi.KtObjectLiteralExpression
import dev.ide.kotlin.syntax.psi.KtPropertyAccessor
import dev.ide.kotlin.syntax.psi.KtSuperTypeCallEntry
import dev.ide.kotlin.syntax.psi.KtUnaryExpression
import dev.ide.lang.kotlin.symbols.KotlinType
import dev.ide.kotlin.syntax.psi.KtTypeReference
import dev.ide.kotlin.syntax.psi.KtValueArgument
import dev.ide.kotlin.syntax.psi.KtWhenConditionIsPattern
import dev.ide.kotlin.syntax.psi.KtWhenConditionWithExpression
import dev.ide.kotlin.syntax.psi.KtWhenEntry
import dev.ide.kotlin.syntax.psi.KtWhenExpression
import dev.ide.kotlin.syntax.psi.KtWhileExpression
import dev.ide.kotlin.syntax.psi.KtWhileExpressionBase

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
 *  - a `val` local variable without a delegate, and a local `var` that is never written after its initializer
 *    (it is a `val` in all but name);
 *  - a function / lambda / `for` / `catch` parameter (immutable).
 * NOT stable (we back off — return false, never smart-cast):
 *  - a reassigned `var` local (effective-immutability needs reassignment/closure analysis; [KotlinVarNullFlow]);
 *  - a delegated local (`val x by …` — the delegate's getValue may vary);
 *  - any member / classifier-scope property, and any qualified receiver (`a.b`) — module/open/custom-getter
 *    stability isn't decidable soundly in the parse-only model (a conservative miss; the common
 *    same-file `private val x: T?` pattern is a candidate refinement);
 *  - an unresolved name.
 */

/** Whether the simple name [name] used at [offset] is smart-cast to a non-null value — honouring the stability
 *  rules above. Combines a null-guard ([nullGuardedNonNullAt]) and an `is T` narrowing (both imply non-null). */
internal fun KotlinResolver.smartCastNonNull(ref: KtNameReferenceExpression, strict: Boolean = false): Boolean {
    val name = ref.getReferencedName()
    val offset = ref.textRange.startOffset
    if (stableForSmartCast(name, offset, ref)) {
        // An immutable stable sink (val local / parameter / final val member) — position-based narrowing is sound
        // (no reassignment can invalidate it) and carries into nested lambdas.
        if (nullGuardedNonNullAt(name, offset, aliasPathOf(ref))) return true
        val narrowed =
            smartCastTypeAt(name, offset) // an `is T` narrowing yields a non-null classifier
        if (narrowed != null && !narrowed.nullable) return true
        // The data-flow pass also tracks immutable values, and sees what the walk above does not: a check made
        // through an alias (`var y = x; require(y is String)`), or one inside a lambda called in place.
        return varSmartCastNonNull(ref, strict)
    }
    // Otherwise: an effectively-immutable local `var` may still be smart-cast — that needs reassignment-aware
    // control-flow (a guard is invalidated by a later `x = …`), handled by the CFG var-nullability pass.
    return varSmartCastNonNull(ref, strict)
}

/**
 * The value a `val` local read by [ref] is an alias of (`val y = x` or `val y = x.data`), as a path key, when
 * the root of that value is itself stable. A guard on the original speaks for the alias: both hold the same
 * value, and neither can change. A `var` root is refused, since it may have been reassigned since the copy.
 */
private fun KotlinResolver.aliasPathOf(ref: KtNameReferenceExpression): String? {
    val init = stripAll(localValInitializer(ref)) as? KtExpression ?: return null
    var root: KtExpression = init
    while (root is KtDotQualifiedExpression) root = stripAll(root.receiverExpression) as? KtExpression ?: return null
    val rootRef = root as? KtNameReferenceExpression ?: return null
    if (!stableForSmartCast(rootRef.getReferencedName(), rootRef.textRange.startOffset, rootRef)) return null
    return pathKey(init)
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
    // Whether the walk has left a function or accessor body: a plain constructor parameter is in scope only in
    // the class's initializers, never inside a member.
    var inMember = false
    while (node != null) {
        if (node is KtNamedFunction || node is KtPropertyAccessor || node is KtSecondaryConstructor) inMember = true
        when (node) {
            is KtBlockExpression -> {
                val local = node.statements.firstOrNull {
                    it is KtProperty && it.name == name && it.textRange.endOffset <= offset
                } as? KtProperty
                if (local != null) return (!local.isVar || neverReassigned(local, node)) && !local.hasDelegate()
                // An entry of a destructuring `val (a, b) = …` is a `val` too.
                node.statements.firstOrNull {
                    it is dev.ide.kotlin.syntax.psi.KtDestructuringDeclaration && it.textRange.endOffset <= offset &&
                        it.entries.any { e -> e.name == name }
                }?.let { return !(it as dev.ide.kotlin.syntax.psi.KtDestructuringDeclaration).isVar }
            }

            // A lambda's implicit `it` is its one value parameter, as stable as a named one, and so is a component
            // of a destructured one (`{ (k, v) -> }`).
            is dev.ide.kotlin.syntax.psi.KtFunctionLiteral -> if (name == "it" && !node.hasParameterSpecification) return true
                else node.valueParameters.firstOrNull {
                    it.name == name || it.destructuringDeclaration?.entries?.any { e -> e.name == name } == true
                }?.let { return true }
            is KtFunction -> node.valueParameters.firstOrNull { it.name == name }?.let {
                // A plain value parameter is immutable → stable; a primary-constructor `val`/`var` parameter is a
                // member property, handled in the KtClassOrObject case below with the member-stability rules.
                if (!it.hasValOrVar()) return true
            }

            // A loop variable, a destructured one included (`for ((k, v) in map)`), is a `val` for each iteration.
            is KtForExpression -> node.loopParameter?.takeIf { it.name == name || it.destructuringDeclaration?.entries?.any { e -> e.name == name } == true }
                ?.let { return true }

            is KtCatchClause -> node.catchParameter?.takeIf { it.name == name }?.let { return true }
            // `when (val v = …)`: the subject variable is a `val`.
            is KtWhenExpression -> if (node.subjectVariable?.name == name) return true
            // A member read (bare or `this.name`): a `val` member of THIS file's enclosing class with a backing
            // field, not overridable, is stable (Kotlin spec: private/internal/same-module + not open + no custom
            // getter — same-file ⊂ same-module, and final ⇒ can't be overridden with a getter). `foo.name` on any
            // other receiver, or a `var`/open/custom-getter member, is not stable. Continue outward when the name
            // isn't a member here (a nested class may read an outer member).
            is KtClassOrObject -> if (bareOrThis) when (stableValMemberKind(node, name)) {
                MemberStability.STABLE -> return true
                MemberStability.UNSTABLE -> return false
                // A plain primary-constructor parameter read by a property initializer or an `init` block is a
                // parameter like any other, and as immutable (`class A(a: String?) { val b = if (a != null) a.length else 1 }`).
                MemberStability.ABSENT -> {
                    if (!inMember &&
                        node.primaryConstructor?.valueParameters?.any { it.name == name && !it.hasValOrVar() } == true
                    ) return true
                    // Inherited from a source superclass: as stable as it is there (`protected val x` read in a
                    // subclass of the same module).
                    when (inheritedValMemberKind(node, name)) {
                        MemberStability.STABLE -> return true
                        MemberStability.UNSTABLE -> return false
                        MemberStability.ABSENT -> {}
                    }
                }
            }
        }
        node = node.parent
    }
    return false
}

/**
 * Whether the local `var` [local], declared in [block], is never written after its initializer: no `=`, compound
 * assignment or `++`/`--` anywhere in the block names it. Such a `var` is effectively a `val`, so it is as stable
 * as one. Matched by name, so a same-named variable elsewhere in the block only makes this more cautious.
 */
private fun neverReassigned(local: KtProperty, block: KtBlockExpression): Boolean {
    val name = local.name ?: return false
    var written = false
    fun target(e: KtExpression?) = (stripAll(e) as? KtNameReferenceExpression)?.getReferencedName() == name
    fun visit(p: KtElement) {
        if (written) return
        when (p) {
            is KtBinaryExpression -> if (p.operationToken in ASSIGNMENTS && target(p.left)) { written = true; return }
            is KtUnaryExpression -> if ((p.operationToken == KtTokens.PLUSPLUS || p.operationToken == KtTokens.MINUSMINUS) &&
                target(p.baseExpression)) { written = true; return }
        }
        var c = p.firstChild
        while (c != null) { visit(c); c = c.nextSibling }
    }
    visit(block)
    return !written
}

internal val ASSIGNMENTS = setOf(
    KtTokens.EQ, KtTokens.PLUSEQ, KtTokens.MINUSEQ, KtTokens.MULTEQ, KtTokens.DIVEQ, KtTokens.PERCEQ,
)

private enum class MemberStability { STABLE, UNSTABLE, ABSENT }

/** [stableValMemberKind] of [name] in the nearest source superclass of [cls] that declares it. */
private fun KotlinResolver.inheritedValMemberKind(cls: KtClassOrObject, name: String): MemberStability {
    val start = cls.fqName?.asString() ?: return MemberStability.ABSENT
    val seen = HashSet<String>()
    val queue = ArrayDeque(service.supertypesOf(start).map { it.qualifiedName })
    while (queue.isNotEmpty() && seen.size < 16) {
        val fqn = queue.removeFirst()
        if (!seen.add(fqn)) continue
        val raw = service.sourceClass(fqn) ?: continue
        val psi = (raw.node as? dev.ide.lang.kotlin.parse.KotlinDomNode)?.psi as? KtClassOrObject ?: continue
        val kind = stableValMemberKind(psi, name)
        if (kind != MemberStability.ABSENT) return kind
        service.supertypesOf(fqn).forEach { queue.addLast(it.qualifiedName) }
    }
    return MemberStability.ABSENT
}

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

/**
 * The types the flow proves [ref] to be an instance of at its position. Every `is`/`as`/`as?`/`when`-pattern
 * target written against the same name in this file is a candidate, and each is put to the same walk the null
 * checks use ([flowProvenAt], with a type goal): `x as B; x.foo()`, `(a as? String)!!; a.length`,
 * `when { x !is A -> {} ; x is B -> x.foo() }` (where `x` is both an `A` and a `B`). Only a stable value is
 * narrowed (see [stableForSmartCast]), and only by a candidate written before the use. [deep] as in [Goal]:
 * false when asked from inside type inference.
 */
internal fun KotlinResolver.flowProvenTypes(ref: KtNameReferenceExpression, deep: Boolean): List<KotlinType> {
    val name = ref.getReferencedName()
    val alias = aliasPathOf(ref)
    val targets = castTargetsByName(flowTableScope(ref))
    val candidates = targets[name].orEmpty() + (alias?.let { targets[it] }.orEmpty())
    val offset = ref.textRange.startOffset
    if (candidates.none { it.textRange.startOffset < offset }) return emptyList()
    if (!stableForSmartCast(name, offset, ref)) return emptyList()
    val out = ArrayList<KotlinType>()
    val seen = HashSet<String>()
    for (target in candidates) {
        if (target.textRange.startOffset >= offset) continue
        val key = erasedTypeText(target.text ?: continue)
        if (!seen.add(key)) continue
        val g = Goal(nameMatcher(name, alias), key, deep, name = name)
        if (flowProvenAt(ref, offset, g, name, stopAt = null)) typeFromIsTarget(target)?.let { t ->
            // Proven only nullable (`a as String?`) unless the flow also proves the value non-null.
            out += if (isNonNullIsTarget(target.text) || flowProvenNonNull(ref)) t else t.withNullable(true)
        }
    }
    return out
}

/** Whether the flow proves the stable value [ref] reads is non-null at its position. Type inference asks this,
 *  so the walk stays shallow ([Goal.deep] off) and never resolves a call. */
internal fun KotlinResolver.flowProvenNonNull(ref: KtNameReferenceExpression): Boolean {
    val name = ref.getReferencedName()
    val offset = ref.textRange.startOffset
    if (!flowMayCheck(ref)) return false
    if (!stableForSmartCast(name, offset, ref)) return false
    val g = Goal(nameMatcher(name, aliasPathOf(ref)), type = null, deep = false, name = name)
    return flowProvenAt(ref, offset, g, name, stopAt = null)
}

/** Whether the flow proves the stable value [ref] reads is `null` at its position (`if (i == null) foo(i)`), so
 *  that it fits any nullable type whatever its declared one. */
internal fun KotlinResolver.flowProvenNull(ref: KtNameReferenceExpression): Boolean {
    val name = ref.getReferencedName()
    val offset = ref.textRange.startOffset
    if (!flowMayCheck(ref)) return false
    if (!stableForSmartCast(name, offset, ref)) return false
    val g = Goal(nameMatcher(name, aliasPathOf(ref)), type = null, deep = false, provesNull = true, name = name)
    return flowProvenAt(ref, offset, g, name, stopAt = null)
}

/**
 * The access path [e] reads, for completion's smart-cast ranking: a simple name, an unlabeled `this`, or a chain of
 * names over one (`holder.box.value`), parentheses, labels and `!!` looked through. Null for anything else (a call,
 * an index, a safe call), which completion does not narrow.
 */
internal fun completionAccessPath(e: KtElement?): String? = when (val s = stripAll(e)) {
    is KtNameReferenceExpression -> s.getReferencedName()
    is KtThisExpression -> if (s.getLabelName() == null) "this" else null
    is KtPostfixExpression -> if (s.operationToken == KtTokens.EXCLEXCL) completionAccessPath(s.baseExpression) else null
    is KtDotQualifiedExpression -> {
        val sel = s.selectorExpression as? KtNameReferenceExpression
        val recv = completionAccessPath(s.receiverExpression)
        if (sel != null && recv != null) recv + "." + sel.getReferencedName() else null
    }
    else -> null
}

/** The `is`/`as`/`when`-pattern targets this file tests each access path ([completionAccessPath]) against. A walk of the
 *  whole file: completion asks it once per request, and only where an expected type ranks the candidates. */
internal fun KotlinResolver.completionCastTargets(): Map<String, List<KtTypeReference>> {
    val out = HashMap<String, MutableList<KtTypeReference>>()
    fun add(subject: KtElement?, ref: KtTypeReference?) {
        val path = completionAccessPath(subject) ?: return
        out.getOrPut(path) { ArrayList() } += ref ?: return
    }
    fun visit(p: KtElement) {
        when (p) {
            is KtIsExpression -> add(p.leftHandSide, p.typeReference)
            is KtBinaryExpressionWithTypeRHS -> add(p.left, p.right)
            is KtWhenExpression -> {
                val subject = p.subjectExpression
                for (entry in p.entries) for (c in entry.conditions) {
                    if (c is KtWhenConditionIsPattern) add(subject, c.typeReference)
                }
            }
        }
        var c = p.firstChild
        while (c != null) { visit(c); c = c.nextSibling }
    }
    visit(ktFile)
    return out
}

/**
 * What the flow proves at [offset] about the value read by the access path [path] (see [completionAccessPath]), for
 * completion's expected-type ranking (IntelliJ's smart-cast info on a candidate, `ExpectedTypeWeigher`): the types
 * the checks in [targets] ([completionCastTargets]) prove it to be there, and whether a null check proves it
 * non-null. The same walk the editor's own smart casts take, asked from the caret for a name not yet written there.
 * A path through a value that is not stable is answered as the walk sees it: this only ranks, it never decides
 * what code means.
 */
internal fun KotlinResolver.completionSmartCast(
    path: String, offset: Int, targets: Map<String, List<KtTypeReference>>,
): Pair<List<KotlinType>, Boolean> {
    val start = elementAt(offset) ?: return emptyList<KotlinType>() to false
    val root = path.substringBefore('.')
    val matches: Matcher = { e -> completionAccessPath(e) == path }
    val types = ArrayList<KotlinType>()
    val seen = HashSet<String>()
    for (target in targets[path].orEmpty()) {
        if (target.textRange.startOffset >= offset) continue
        val key = erasedTypeText(target.text ?: continue)
        if (!seen.add(key)) continue
        val g = Goal(matches, key, deep = true, name = root.takeIf { '.' !in path })
        if (flowProvenAt(start, offset, g, root, stopAt = null)) typeFromIsTarget(target)?.let { types += it }
    }
    val nonNull = types.isNotEmpty() ||
        flowProvenAt(start, offset, Goal(matches, type = null, deep = true, name = root.takeIf { '.' !in path }), root, stopAt = null)
    return types to nonNull
}

/**
 * The names this file writes a shallow null proof against: the operand of `!!`, of an equality, of `is`/`as`, the
 * left of `?:`, the root of a `?.` chain, a `when` subject, the receiver of `isNullOrEmpty`/`isNullOrBlank`, the
 * argument of a precondition. A proof without resolution ([Goal.deep] off) always names its value in one of these
 * shapes (or through a `val` whose initializer does), so a name outside the set needs no walk.
 */
private fun KotlinResolver.flowCheckedNames(scope: KtElement): Set<String> = caches.flowCheckedNames.getOrPut(scope) {
    val out = HashSet<String>()
    fun add(e: KtElement?) {
        var cur = stripAll(e)
        while (cur is KtQualifiedExpression) cur = stripAll(cur.receiverExpression)
        (cur as? KtNameReferenceExpression)?.let { out += it.getReferencedName() }
    }
    fun visit(p: KtElement) {
        when (p) {
            is KtPostfixExpression -> if (p.operationToken == KtTokens.EXCLEXCL) add(p.baseExpression)
            is KtBinaryExpression -> when (p.operationToken) {
                KtTokens.EQEQ, KtTokens.EXCLEQ, KtTokens.EQEQEQ, KtTokens.EXCLEQEQEQ -> { add(p.left); add(p.right) }
                KtTokens.ELVIS -> add(p.left)
                else -> {}
            }
            is KtIsExpression -> add(p.leftHandSide)
            is KtBinaryExpressionWithTypeRHS -> add(p.left)
            is KtSafeQualifiedExpression -> add(p)
            is KtWhenExpression -> { add(whenSubjectValue(p)); p.subjectVariable?.name?.let { out += it } }
            is KtDotQualifiedExpression -> if (p.selectorExpression?.text?.startsWith("isNullOr") == true) add(p.receiverExpression)
            is KtCallExpression -> if ((p.calleeExpression as? KtNameReferenceExpression)?.getReferencedName() in PRECONDITIONS) {
                p.valueArguments.forEach { add(it.getArgumentExpression()) }
            }
        }
        var c = p.firstChild
        while (c != null) { visit(c); c = c.nextSibling }
    }
    visit(scope)
    out
}

/**
 * The subtree the per-name flow tables ([flowCheckedNames], [castTargetsByName]) are built over for a value read at
 * [ref]: the member FUNCTION, accessor or secondary constructor holding it when there is one, else the file.
 *
 * A null check or cast proves something only inside the flow it is written in, and no flow crosses from one
 * member function into another, so a function's own checks are all its reads can use. Building the tables over
 * the whole file instead made typing ONE declaration of another file walk all of that file, parsing every lazily
 * collapsed body and wrapping every node, which then stayed in memory with the file's parse. Property
 * initializers and `init` blocks share the constructor's flow, so a read there keeps the whole-file table.
 */
private fun flowTableScope(ref: KtElement): KtElement {
    var e: KtElement? = ref.parent
    var member: KtElement? = null
    while (e != null) {
        when (e) {
            is KtPropertyAccessor, is KtSecondaryConstructor -> member = e
            is KtNamedFunction -> if (e.parent is KtClassBody || e.parent is KtFile) member = e
        }
        e = e.parent
    }
    return member ?: ref.containingKtFile
}

/** Whether [ref]'s name, or the value it is a `val` copy of, is in [flowCheckedNames]. */
private fun KotlinResolver.flowMayCheck(ref: KtNameReferenceExpression): Boolean {
    val names = flowCheckedNames(flowTableScope(ref))
    if (ref.getReferencedName() in names) return true
    val alias = aliasPathOf(ref) ?: return false
    return alias.substringBefore('.') in names
}

private val PRECONDITIONS = setOf("requireNotNull", "checkNotNull", "require", "check")

/** The type references each simple name is tested or cast against in this file (see [KotlinResolverCaches.castTargets]). */
private fun KotlinResolver.castTargetsByName(scope: KtElement): Map<String, List<KtTypeReference>> = caches.castTargets.getOrPut(scope) {
    val out = HashMap<String, MutableList<KtTypeReference>>()
    fun add(subject: KtElement?, ref: KtTypeReference?) {
        val name = (stripAll(subject) as? KtNameReferenceExpression)?.getReferencedName() ?: return
        out.getOrPut(name) { ArrayList() } += ref ?: return
    }
    fun visit(p: KtElement) {
        when (p) {
            is KtIsExpression -> add(p.leftHandSide, p.typeReference)
            is KtBinaryExpressionWithTypeRHS -> add(p.left, p.right)
            is KtWhenExpression -> {
                val subjects = listOfNotNull(p.subjectVariable?.initializer, p.subjectExpression)
                for (entry in p.entries) for (c in entry.conditions) {
                    if (c !is KtWhenConditionIsPattern) continue
                    subjects.forEach { add(it, c.typeReference) }
                    p.subjectVariable?.name?.let { v -> out.getOrPut(v) { ArrayList() } += c.typeReference ?: return@let }
                }
            }
            // `assertIs<T>(x)`: a library contract can say `x is T` of a type argument. A candidate only; the
            // flow walk decides whether the call's contract proves it.
            is KtCallExpression -> if (p.typeArguments.isNotEmpty()) for (arg in p.valueArguments) {
                for (t in p.typeArguments) add(arg.getArgumentExpression(), t.typeReference)
            }
        }
        var c = p.firstChild
        while (c != null) { visit(c); c = c.nextSibling }
    }
    visit(scope)
    out
}

/** Whether a null guard in flow scope proves [name] is non-null at [offset]. See [flowProvenAt] for the
 *  constructs read. [aliasPath] is the access path [name] is a `val` alias of (`val y = x.data`), whose guards
 *  speak for [name] too. SOUND ONLY for a stable immutable value: [smartCastNonNull] gates on
 *  [stableForSmartCast], so reassignment can't invalidate a conclusion. */
internal fun KotlinResolver.nullGuardedNonNullAt(name: String, offset: Int, aliasPath: String? = null): Boolean {
    val g = Goal(nameMatcher(name, aliasPath), type = null, deep = true, name = name)
    return flowProvenAt(elementAt(offset) ?: return false, offset, g, name, stopAt = null)
}

/** As [nullGuardedNonNullAt] but matches a QUALIFIED access path by its text (`b.s`, `this.x`), with `?.` read
 *  as `.` and `!!` dropped, so a guard on `a?.b` speaks for `a.b`. Used to suppress a false unsafe-call on a
 *  guarded member dereference (`if (b.s != null) b.s.length`). Since it only ever SUPPRESSES an error,
 *  text-matching is sound: at worst a reassignment inside the path under-reports. */
internal fun KotlinResolver.pathGuardedNonNullAt(path: String, offset: Int): Boolean {
    val key = pathKey(path)
    // A member the source shows to be unstable (a `var`, a custom getter, an open or abstract property) is never
    // smart-cast, whatever was checked about it: Kotlin reports SMARTCAST_IMPOSSIBLE there.
    var at: KtElement? = elementAt(offset)
    while (at != null && at.textRange.startOffset == offset && pathKey(at) != key) at = at.parent
    if (at is KtQualifiedExpression && !pathMembersStable(at)) return false
    val root = key.substringBefore('.').substringBefore('(').substringBefore('[')
    // A variable some lambda writes may change under any fact about it (Kotlin: a captured, modified variable).
    if (at != null && writtenInClosure(at, root)) return false
    val g = Goal({ e -> stripAll(e)?.let { pathKey(it) } == key }, type = null, deep = true)
    return flowProvenAt(elementAt(offset) ?: return false, offset, g, root, stopAt = null)
}

/**
 * Whether every member read along the access path [expr] (`q.data.s`) is a stable smart-cast sink as far as the
 * source shows. A source property that is a `var`, has a custom getter or a delegate, or can be overridden (open,
 * abstract, an interface member, a non-final override in an open class) is not. A library member is not judged
 * here and passes, as before.
 */
private fun KotlinResolver.pathMembersStable(expr: KtExpression): Boolean {
    var root: KtExpression? = stripAll(expr) as? KtExpression
    while (root is KtQualifiedExpression) root = stripAll(root.receiverExpression) as? KtExpression
    // A reassigned `var` root holds whatever was last assigned to it (`a = FinalImpl()`), which Kotlin tracks and
    // the declared type does not show. Judged through that assignment when it is the last write before the use in
    // the same block; otherwise not judged at all, rather than against the declared class.
    var rootType: KotlinType? = null
    if (root is KtNameReferenceExpression &&
        !stableForSmartCast(root.getReferencedName(), root.textRange.startOffset, root)) {
        rootType = lastAssignedType(root) ?: return true
    }
    var cur: KtExpression? = stripAll(expr) as? KtExpression
    var depth = 0
    while (cur is KtQualifiedExpression && depth++ < 8) {
        val sel = cur.selectorExpression as? KtNameReferenceExpression ?: return true
        val recvExpr = stripAll(cur.receiverExpression)
        val recvType = if (recvExpr === root && rootType != null) rootType else inferType(cur.receiverExpression)
        if (recvType != null && !recvType.isTypeParameter && !sourceMemberStable(recvType, sel.getReferencedName())) return false
        cur = stripAll(cur.receiverExpression) as? KtExpression
    }
    return true
}

/**
 * Whether the property [name] a value of [owner] reads is stable, judged at the most derived SOURCE declaration of
 * it: [owner]'s class, else the first of its superclasses that declares it (so `final override val a` in a
 * subclass of `open val a` is stable there, and an inherited `open val a` is not). A class the source does not
 * declare stops the search with no verdict (stable), as does a name no source class declares.
 */
/**
 * The type of the value last assigned to the `var` [ref] reads, when that is plain to see: walking back through
 * the statements before the use, in each enclosing block up to the function, the first one that writes the
 * variable is `v = <expr>` itself, or there is none before its declaration (then its declared type). Anything
 * else that writes it (a nested or compound write, or a write in a loop or lambda around the use) gives no answer.
 */
private fun KotlinResolver.lastAssignedType(ref: KtNameReferenceExpression): KotlinType? {
    val name = ref.getReferencedName()
    var child: KtElement = ref
    var node = ref.parent
    while (node != null && node !is KtDeclarationWithBody && node !is KtClassOrObject) {
        if (node is KtBlockExpression) {
            for (st in node.statements.takeWhile { it !== child }.asReversed()) {
                // Reached the declaration with no write in between: the declared type is what it holds.
                if (st is KtProperty && st.name == name) return inferType(ref)?.takeIf { !it.nullable && !it.isTypeParameter }
                if (!writesName(st, name)) continue
                val assign = stripAll(st) as? KtBinaryExpression ?: return null
                if (assign.operationToken != KtTokens.EQ) return null
                if ((stripAll(assign.left) as? KtNameReferenceExpression)?.getReferencedName() != name) return null
                return inferType(assign.right)?.takeIf { !it.nullable && !it.isTypeParameter }
            }
        }
        if (node is KtLoopExpression || node is KtFunctionLiteral) return null
        child = node
        node = node.parent
    }
    return null
}

private fun KotlinResolver.sourceMemberStable(owner: KotlinType, name: String): Boolean {
    val seen = HashSet<String>()
    val queue = ArrayDeque(listOf(owner.qualifiedName))
    fun isFinal(cls: KtClassOrObject) = (cls as? KtClass)?.isInterface() != true && !cls.hasModifier(KtTokens.OPEN_KEYWORD) &&
        !cls.hasModifier(KtTokens.ABSTRACT_KEYWORD) && !cls.hasModifier(KtTokens.SEALED_KEYWORD)
    // A final receiver class closes every member it inherits: nothing can override them for this value.
    val ownerFinal = service.sourceClass(owner.qualifiedName)
        ?.let { (it.node as? dev.ide.lang.kotlin.parse.KotlinDomNode)?.psi as? KtClassOrObject }?.let(::isFinal) == true
    while (queue.isNotEmpty()) {
        val fqn = queue.removeFirst()
        if (!seen.add(fqn) || seen.size > 16) continue
        val raw = service.sourceClass(fqn) ?: continue
        val cls = (raw.node as? dev.ide.lang.kotlin.parse.KotlinDomNode)?.psi as? KtClassOrObject ?: continue
        val classFinal = ownerFinal || isFinal(cls)
        fun overridable(d: dev.ide.kotlin.syntax.psi.KtModifierListOwner) = !classFinal &&
            (d.hasModifier(KtTokens.OPEN_KEYWORD) || d.hasModifier(KtTokens.ABSTRACT_KEYWORD) ||
                (d.hasModifier(KtTokens.OVERRIDE_KEYWORD) && !d.hasModifier(KtTokens.FINAL_KEYWORD)) ||
                (cls as? KtClass)?.isInterface() == true)
        val prop = cls.declarations.firstOrNull { it is KtProperty && it.name == name } as? KtProperty
        if (prop != null) return !prop.isVar && prop.getter == null && !prop.hasDelegate() && !overridable(prop)
        val param = cls.primaryConstructor?.valueParameters?.firstOrNull { it.name == name && it.hasValOrVar() }
        if (param != null) return !param.isMutable && !overridable(param)
        service.supertypesOf(fqn).forEach { queue.addLast(it.qualifiedName) }
    }
    return true
}

/** Whether a lambda or local function in the function around [from] writes [name]. */
private fun writtenInClosure(from: KtElement, name: String): Boolean {
    val fn = from.getStrictParentOfType<KtNamedFunction>() ?: return false
    var found = false
    fun visit(p: KtElement, inClosure: Boolean) {
        if (found) return
        if (inClosure && p is KtExpression && p !is KtBlockExpression && writesName(p, name) &&
            (p is KtBinaryExpression || p is KtUnaryExpression)) { found = true; return }
        var c = p.firstChild
        while (c != null) { visit(c, inClosure || c is KtFunctionLiteral || c is KtNamedFunction); c = c.nextSibling }
    }
    visit(fn, false)
    return found
}

/** Whether [e] writes the variable [name], or a member reached through it (`name = …`, `name.x = …`, `name++`). */
private fun writesName(e: KtElement, name: String): Boolean {
    fun root(t: KtExpression?): String? {
        var cur = stripAll(t)
        while (cur is KtQualifiedExpression) cur = stripAll(cur.receiverExpression)
        if (cur is KtArrayAccessExpression) return root(cur.arrayExpression)
        return (cur as? KtNameReferenceExpression)?.getReferencedName()
    }
    var found = false
    fun visit(p: KtElement) {
        if (found) return
        when (p) {
            is KtBinaryExpression -> if (p.operationToken in ASSIGNMENTS && root(p.left) == name) { found = true; return }
            is KtUnaryExpression -> if ((p.operationToken == KtTokens.PLUSPLUS || p.operationToken == KtTokens.MINUSMINUS) &&
                root(p.baseExpression) == name) { found = true; return }
        }
        var c = p.firstChild
        while (c != null) { visit(c); c = c.nextSibling }
    }
    visit(e)
    return found
}

/** The text an access path is compared by: no whitespace, `?.` read as `.`, and `!!` dropped. */
internal fun pathKey(e: KtElement): String = pathKey(e.text ?: "")

private fun pathKey(text: String): String =
    text.filterNot { it.isWhitespace() }.replace("?.", ".").replace("!!", "")

/** Decides whether an expression denotes the value a smart cast is asked about (a name, or an access path). */
private typealias Matcher = (KtExpression?) -> Boolean

/** A matcher for the simple name [name], or for the access path [aliasPath] it is a `val` copy of. */
private fun nameMatcher(name: String, aliasPath: String?): Matcher = { e ->
    when (val s = stripAll(e)) {
        null -> false
        is KtNameReferenceExpression -> s.getReferencedName() == name || aliasPath == s.getReferencedName()
        else -> aliasPath != null && pathKey(s) == aliasPath
    }
}

/**
 * What the flow walk sets out to prove about the value [matches] picks out: that it is non-null ([type] null), that
 * it is an instance of the type written [type] (erased, without `?`, as the source spells it in an `is`/`as`), or,
 * with [provesNull], that it IS null (`if (x == null) <here>`).
 * [deep] allows the reasoning that needs resolution (a `Nothing` call, a source contract); it is off when the walk
 * runs from inside type inference, where resolving a call could re-enter the inference that asked.
 */
private class Goal(
    val matches: Matcher, val type: String?, val deep: Boolean, val provesNull: Boolean = false,
    /** The simple name the value is read by, when it is one: a `when (val v = …)` subject variable of that name is it. */
    val name: String? = null,
) {
    val nonNull: Boolean get() = type == null && !provesNull

    /** Whether [ref], the target of an `is`/`as` on the value, proves this goal. A nullable target
     *  (`a as String?`) proves the type without proving non-null: K2 records `a: String?`, which a later `!!` on
     *  the same value then makes a `String`. */
    fun target(ref: KtTypeReference?): Boolean {
        if (provesNull) return false
        val text = ref?.text ?: return false
        return if (type == null) isNonNullIsTarget(text) else erasedTypeText(text) == type
    }

    /** Whether [e] evaluates to the value itself: it is, or it is a `val` copy, `!!` or `as` of it. */
    fun sameValue(e: KtExpression?, depth: Int = 0): Boolean {
        if (depth > 8) return false
        val x = stripAll(e) as? KtExpression ?: return false
        if (matches(x)) return true
        return when (x) {
            is KtPostfixExpression -> x.operationToken == KtTokens.EXCLEXCL && sameValue(x.baseExpression, depth + 1)
            is KtBinaryExpressionWithTypeRHS -> isHardCast(x) && sameValue(x.left, depth + 1)
            is KtNameReferenceExpression -> localValInitializer(x)?.let { sameValue(it, depth + 1) } == true
            else -> false
        }
    }
}

/** The text a cast target is compared by: type arguments and a trailing `?` dropped, no whitespace. */
internal fun erasedTypeText(text: String): String =
    text.substringBefore('<').filterNot { it.isWhitespace() }.removeSuffix("?")

private fun isHardCast(x: KtBinaryExpressionWithTypeRHS): Boolean =
    x.operationReference.getReferencedNameElementType() == KtTokens.AS_KEYWORD

/** How deep the completion reasoning may recurse into one statement before it gives up (and proves nothing). */
private const val MAX_PROOF_DEPTH = 48

/**
 * The flow walk: whether everything that is DEFINITELY evaluated before [offset] proves the goal [g] (the matched
 * value is non-null, is a `T`, or is null; described below for non-null, the other two read the same way).
 *
 * Climbs from [start] to the root. At each ancestor it asks what that node evaluates, unconditionally and before
 * the child holding the use, and whether completing that normally implies the value is non-null
 * ([completionProves]): the earlier statements of a block, the earlier arguments of a call, the receiver of a
 * qualified access, the left operand of a binary, the condition of an `if`/`while` (plus what the condition being
 * true or false guarantees, [condGuarantees]), the body of a `do`-`while` before its condition, the subject and the
 * earlier failed branches of a `when`, and a secondary constructor's delegation call before its body.
 *
 * This is a definite-assignment style argument rather than a fixpoint, and it is sound because the value is
 * immutable: a fact established at any point before the use still holds at the use. A use inside a lambda sees
 * the facts established before the lambda was created, for the same reason.
 *
 * [rootName] is the name the matched value is reached through. The walk stops at the declaration that binds it
 * (a parameter, a local, a loop variable, a lambda's `it`), so a guard on an outer variable of the same name never
 * speaks for an inner one. [stopAt] bounds the walk to one subtree (used to judge a `break` inside its loop).
 */
private fun KotlinResolver.flowProvenAt(
    start: KtElement, offset: Int, g: Goal, rootName: String?, stopAt: KtElement?,
): Boolean {
    var child: KtElement = start
    var node: KtElement? = start.parent
    while (node != null) {
        if (nodeProves(node, child, offset, g, rootName)) return true
        if (node === stopAt || bindsName(node, child, offset, rootName)) return false
        // A write to the value (only possible for an access path over a `var`) undoes every fact from before it:
        // in this block before the use, or anywhere in a loop the use repeats in.
        if (rootName != null) {
            val written = when (node) {
                is KtBlockExpression -> node.statements.takeWhile { it !== child }.any { writesName(it, rootName) }
                is KtLoopExpression -> writesName(node, rootName)
                else -> false
            }
            if (written) return false
        }
        child = node
        node = node.parent
    }
    return false
}

/** What [node] alone evaluates before [child] (the node on the path to [offset]) and whether that proves the value. */
private fun KotlinResolver.nodeProves(
    node: KtElement, child: KtElement, offset: Int, g: Goal, rootName: String?,
): Boolean {
    fun KtElement?.holds() = this?.textRange?.contains(offset) == true
    return when (node) {
        is KtIfExpression -> when {
            node.then.holds() -> condGuarantees(node.condition, true, g) || completionProves(node.condition, g)
            node.`else`.holds() -> condGuarantees(node.condition, false, g) || completionProves(node.condition, g)
            else -> false
        }

        is KtWhileExpression -> node.body.holds() &&
            (condGuarantees(node.condition, true, g) || completionProves(node.condition, g))

        // The condition of a `do`-`while` runs after the body has completed, or after a `continue` skipped its rest.
        is KtDoWhileExpression -> node.condition.holds() &&
            completionProves(node.body, g) && loopJumpsProven(node, g, rootName, continues = true)

        is KtForExpression -> node.body.holds() && completionProves(node.loopRange, g)

        is KtBinaryExpression -> node.right.holds() && when (node.operationToken) {
            KtTokens.ANDAND -> condGuarantees(node.left, true, g) || completionProves(node.left, g)
            KtTokens.OROR -> condGuarantees(node.left, false, g) || completionProves(node.left, g)
            else -> completionProves(node.left, g)
        }

        is KtBlockExpression -> {
            var proven = false
            for (st in node.statements) {
                if (st === child) break
                // A redeclaration or a write undoes what came before it; the write itself may prove the new value
                // (`x = y!!` on a `val` declared without an initializer).
                if (rootName != null && st is KtProperty && st.name == rootName) proven = false
                else if (rootName != null && writesName(st, rootName)) proven = !neverCompletes(st) && completionProves(st, g)
                // A statement that never completes leaves the use dead, where Kotlin keeps no smart cast at all:
                // it proves nothing (rather than everything).
                else if (!proven && !neverCompletes(st) && completionProves(st, g)) proven = true
            }
            proven
        }

        is KtCallExpression -> argumentsBeforeProve(node.valueArguments, offset, g)
        is KtConstructorDelegationCall -> argumentsBeforeProve(node.valueArguments, offset, g)

        // `foo(x!!)?.bar(x.length)`: the receiver ran first. Past a `?.`, the receiver is also non-null.
        is KtDotQualifiedExpression -> node.selectorExpression.holds() && completionProves(node.receiverExpression, g)
        is KtSafeQualifiedExpression -> node.selectorExpression.holds() &&
            (completionProves(node.receiverExpression, g) || valueProves(node.receiverExpression, g))

        is KtArrayAccessExpression -> !node.arrayExpression.holds() && (completionProves(node.arrayExpression, g) ||
            node.indexExpressions.takeWhile { !it.holds() }.any { completionProves(it, g) })

        is KtWhenExpression -> {
            val i = node.entries.indexOfFirst { it.expression.holds() }
            // A condition of a later branch runs only once the earlier ones failed (`s == null -> …; s.foo() -> …`).
            val c = if (i >= 0) -1 else node.entries.indexOfFirst { e -> e.conditions.any { it.holds() } || e.guard.holds() }
            when {
                i >= 0 -> completionProves(whenSubjectValue(node), g) || whenEntryReachedProves(node, i, g) ||
                    firstConditionCompletes(node.entries.firstOrNull(), g) || firstConditionCompletes(node.entries[i], g)
                c >= 0 -> completionProves(whenSubjectValue(node), g) || whenPrefixFalseProves(node, c, g) ||
                    (c > 0 && firstConditionCompletes(node.entries.firstOrNull(), g))
                else -> false
            }
        }

        is KtSecondaryConstructor -> node.bodyExpression.holds() &&
            node.getDelegationCall().valueArguments.any { completionProves(it.getArgumentExpression(), g) }

        else -> false
    }
}

/** Whether a value argument before the one holding [offset] completes only with the value proven non-null. */
private fun KotlinResolver.argumentsBeforeProve(args: List<KtValueArgument>, offset: Int, g: Goal): Boolean {
    for (arg in args) {
        if (arg.textRange.contains(offset)) return false
        if (completionProves(arg.getArgumentExpression(), g)) return true
    }
    return false
}

/** Whether [node] declares the name the walk follows, so that nothing above it can speak for this binding. */
private fun bindsName(node: KtElement, child: KtElement, offset: Int, rootName: String?): Boolean {
    if (rootName == null) return false
    return when (node) {
        is KtBlockExpression -> node.statements.takeWhile { it !== child }.any { it is KtProperty && it.name == rootName }
        is KtFunctionLiteral -> if (node.hasParameterSpecification) node.valueParameters.any { it.name == rootName }
            else rootName == "it"
        is KtFunction -> node.valueParameters.any { it.name == rootName }
        is KtForExpression -> node.body?.textRange?.contains(offset) == true && (node.loopParameter?.name == rootName ||
            node.loopParameter?.destructuringDeclaration?.entries?.any { it.name == rootName } == true)
        is KtCatchClause -> node.catchParameter?.name == rootName
        is KtWhenExpression -> node.subjectVariable?.name == rootName
        is KtClassOrObject -> node.declarations.any { it is KtProperty && it.name == rootName }
        else -> false
    }
}

/**
 * Whether [e] completing NORMALLY implies the matched value is non-null. True for an expression that never
 * completes (a jump, a `Nothing` call: vacuously), for `v!!`, `v as T`, `requireNotNull(v)`, `require(v != null)`,
 * a source function's `returns() implies` contract, `v ?: <jump>`, and for any construct one of whose
 * unconditionally evaluated parts does: an `if` whose every completing branch proves it (or whose condition
 * decides it), a loop every exit of which does, a block any statement of which does. Lambda bodies are not
 * looked into: nothing says when, or whether, they run.
 */
private fun KotlinResolver.completionProves(e: KtExpression?, g: Goal, depth: Int = 0): Boolean {
    if (depth > MAX_PROOF_DEPTH) return false
    val x = stripAll(e) as? KtExpression ?: return false
    val d = depth + 1
    return when (x) {
        is KtReturnExpression, is KtThrowExpression, is KtBreakExpression, is KtContinueExpression -> true

        is KtPostfixExpression ->
            (x.operationToken == KtTokens.EXCLEXCL && valueProves(x.baseExpression, g)) || completionProves(x.baseExpression, g, d)

        is KtPrefixExpression -> completionProves(x.baseExpression, g, d)

        // A hard cast throws unless it holds; `as?` (and, for non-null, a cast to a nullable type) guarantees nothing.
        is KtBinaryExpressionWithTypeRHS ->
            (isHardCast(x) && isCheckProves(x.left, x.right, g)) || completionProves(x.left, g, d)

        is KtIsExpression -> completionProves(x.leftHandSide, g, d)

        is KtBinaryExpression -> when (x.operationToken) {
            // `v ?: <jump>`, `v ?: v!!`: the right side runs only when the left is null, and the whole completes
            // either with a non-null left (which, through a safe chain, proves the value) or with the right done.
            KtTokens.ELVIS -> completionProves(x.left, g, d) ||
                (valueProves(x.left, g) && completionProves(x.right, g, d))
            KtTokens.ANDAND, KtTokens.OROR -> completionProves(x.left, g, d)
            // `x = y!!` on a `val` declared without an initializer: its one value is non-null. (A stable value
            // is assigned only that once; a reassigned `var` never reaches this walk.)
            KtTokens.EQ -> (g.nonNull && g.matches(stripAll(x.left) as? KtExpression) && obviouslyNonNull(x.right)) ||
                completionProves(x.left, g, d) || (!safeTarget(x) && completionProves(x.right, g, d))
            // `c?.x = rhs` (and `c?.x += rhs`) evaluates the right side only when `c` is non-null.
            in ASSIGNMENTS -> completionProves(x.left, g, d) || (!safeTarget(x) && completionProves(x.right, g, d))
            else -> completionProves(x.left, g, d) || completionProves(x.right, g, d)
        }

        // `object : A(o!!) {}` runs its supertype constructor's arguments where it is written.
        is KtObjectLiteralExpression -> x.objectDeclaration?.superTypeListEntries.orEmpty()
            .any { e -> e is KtSuperTypeCallEntry && e.valueArguments.any { completionProves(it.getArgumentExpression(), g, d) } }

        is KtIfExpression -> completionProves(x.condition, g, d) ||
            (branchProves(x.then, x.condition, true, g, d) && branchProves(x.`else`, x.condition, false, g, d))

        is KtWhenExpression -> whenCompletionProves(x, g, d)
        is KtBlockExpression -> x.statements.any { completionProves(it, g, d) }
        is KtProperty -> completionProves(x.initializer, g, d)
        is KtCallExpression -> callProves(x, null, g, d)
        is KtDotQualifiedExpression -> completionProves(x.receiverExpression, g, d) ||
            (x.selectorExpression as? KtCallExpression)?.let { callProves(it, x.receiverExpression, g, d) } == true
        is KtSafeQualifiedExpression -> completionProves(x.receiverExpression, g, d)
        is KtArrayAccessExpression -> completionProves(x.arrayExpression, g, d) ||
            x.indexExpressions.any { completionProves(it, g, d) }
        is KtStringTemplateExpression ->
            x.entries.any { completionProves((it as? KtStringTemplateEntryWithExpression)?.expression, g, d) }

        // A `while` is left when its condition is false or through a `break`; the condition always runs first.
        // An endless one with no `break` is never left; what follows is dead, and claims nothing.
        is KtWhileExpression -> !neverCompletes(x) && (completionProves(x.condition, g, d) ||
            ((isTrueLiteral(x.condition) || condGuarantees(x.condition, false, g)) &&
                loopJumpsProven(x, g, null, continues = false)))

        // A `do`-`while` is left when its condition is false (reached after the body, or after a `continue`)
        // or through a `break`.
        is KtDoWhileExpression -> !neverCompletes(x) && run {
            val condExit = isTrueLiteral(x.condition) || condGuarantees(x.condition, false, g) ||
                completionProves(x.condition, g, d) ||
                (completionProves(x.body, g, d) && loopJumpsProven(x, g, null, continues = true))
            condExit && loopJumpsProven(x, g, null, continues = false)
        }

        is KtForExpression -> completionProves(x.loopRange, g, d)

        is KtTryExpression -> completionProves(x.finallyBlock?.finalExpression, g, d) ||
            (completionProves(x.tryBlock, g, d) && x.catchClauses.all { completionProves(it.catchBody, g, d) })

        else -> false
    }
}

/** One arm of an `if`: proven when the condition decides it on this [side], or when completing the arm proves it.
 *  An absent `else` completes at once, so only the condition can speak for it. */
private fun KotlinResolver.branchProves(
    branch: KtExpression?, cond: KtExpression?, side: Boolean, g: Goal, depth: Int,
): Boolean = condGuarantees(cond, side, g) || (branch != null && completionProves(branch, g, depth))

/** A call that completes only when the value is non-null: a stdlib precondition, an argument that proves it,
 *  a source function's `returns() implies` contract, or a call that never returns (`error()`, `TODO()`). */
private fun KotlinResolver.callProves(call: KtCallExpression, receiver: KtExpression?, g: Goal, depth: Int): Boolean {
    val args = call.valueArguments
    if (receiver == null) {
        val callee = (call.calleeExpression as? KtNameReferenceExpression)?.getReferencedName()
        val first = args.takeIf { it.size <= 2 }?.firstOrNull()?.getArgumentExpression()
        when (callee) {
            "requireNotNull", "checkNotNull" -> if (g.nonNull && valueProves(first, g)) return true
            "require", "check" -> if (first != null && condGuarantees(first, true, g)) return true
        }
    }
    if (args.any { completionProves(it.getArgumentExpression(), g, depth) }) return true
    // The stdlib scope functions run their lambda once, in place, before they return (`?: run { return }`).
    val name = (call.calleeExpression as? KtNameReferenceExpression)?.getReferencedName()
    if (name != null && name in (if (receiver == null) BARE_IN_PLACE else DOT_IN_PLACE) && standsAlone(call)) {
        val body = call.lambdaArguments.singleOrNull()?.getLambdaExpression()?.bodyExpression
        if (body != null && !hasLabeledReturn(body) && completionProves(body, g, depth)) return true
    }
    if (!g.deep) return false
    if (returnsLambdaResultThatNeverCompletes(call)) return true
    // Only a call the value is passed to (or called on) can carry a contract about it.
    if ((receiver != null && carriesValue(receiver, g)) || args.any { mentionsValue(it.getArgumentExpression(), g) }) {
        if (contractGuarantees(call, receiver, whenTrue = true, g, completion = true)) return true
    }
    // A `Nothing?` call completes, with `null`; only a `Nothing` one never does.
    return receiver == null && inferType(call)?.let { it.qualifiedName == "kotlin.Nothing" && !it.nullable } == true
}

/**
 * Whether [call] is resolved on its own rather than as part of an enclosing call's inference: no value argument
 * or `when` subject stands between it and its statement. Kotlin carries what an in-place lambda proves only out
 * of such a call; one nested in another call's arguments (`foo(id(run { x as String }), …)`) is postponed, and
 * its effects do not reach the code after it.
 */
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

/** Whether [body] returns to a label (`return@run x`): such a lambda completes there, which the jump reasoning
 *  would otherwise read as "never completes". */
private fun hasLabeledReturn(body: KtElement): Boolean {
    var found = false
    fun visit(p: KtElement) {
        if (found) return
        if (p is KtReturnExpression && p.getLabelName() != null) { found = true; return }
        var c = p.firstChild
        while (c != null) { visit(c); c = c.nextSibling }
    }
    visit(body)
    return found
}

private val BARE_IN_PLACE = setOf("run", "with", "synchronized")
private val DOT_IN_PLACE = setOf("run", "let", "also", "apply", "use")

/**
 * `callIt { return }` where `fun <R> callIt(fn: () -> R): R`: the call's type is the lambda's, and a lambda that
 * never completes is a `() -> Nothing`, so the call is a `Nothing` call too (inference alone leaves `R` open).
 */
private fun KotlinResolver.returnsLambdaResultThatNeverCompletes(call: KtCallExpression): Boolean {
    val body = call.lambdaArguments.singleOrNull()?.getLambdaExpression()?.bodyExpression ?: return false
    val last = body.statements.lastOrNull() ?: return false
    if (!neverCompletes(last) && !(last is KtCallExpression && inferType(last)?.let { it.qualifiedName == "kotlin.Nothing" && !it.nullable } == true)) return false
    val sym = runCatching { resolveCalleeFunction(call) }.getOrNull() ?: return false
    val ret = sym.type as? KotlinType ?: return false
    if (!ret.isTypeParameter) return false
    val fnType = sym.paramTypes.lastOrNull() as? KotlinType ?: return false
    return (fnType.typeArguments.lastOrNull() as? KotlinType)?.let { it.isTypeParameter && it.qualifiedName == ret.qualifiedName } == true
}

/** The value a `when` tests: the initializer of its subject `val`, else its subject expression. */
private fun whenSubjectValue(w: KtWhenExpression): KtExpression? = w.subjectVariable?.initializer ?: w.subjectExpression

/** A `when` completes through one of its branches, or (without an `else`) by matching none of them. */
private fun KotlinResolver.whenCompletionProves(w: KtWhenExpression, g: Goal, depth: Int): Boolean {
    if (completionProves(whenSubjectValue(w), g, depth)) return true
    val entries = w.entries
    if (firstConditionCompletes(entries.firstOrNull(), g, depth)) return true
    if (entries.none { it.isElse } && !whenPrefixFalseProves(w, entries.size, g)) return false
    return entries.indices.all { i ->
        completionProves(entries[i].expression, g, depth) || whenEntryReachedProves(w, i, g)
    }
}

/** Whether the first condition of entry [i] of [w], which runs whenever that entry is reached (the first entry's
 *  always), completes only with the value proven: `when (x) { x as Int -> … }` leaves `x` an `Int` after the `when`. */
private fun KotlinResolver.firstConditionCompletes(entry: KtWhenEntry?, g: Goal, depth: Int = 0): Boolean {
    if (entry == null || entry.isElse) return false
    val c = entry.conditions.firstOrNull() as? KtWhenConditionWithExpression ?: return false
    return completionProves(c.expression, g, depth)
}

/** Whether reaching the body of entry [i] proves the value: its own conditions hold, or an earlier one failed. */
private fun KotlinResolver.whenEntryReachedProves(w: KtWhenExpression, i: Int, g: Goal): Boolean {
    if (whenPrefixFalseProves(w, i, g)) return true
    val entry = w.entries[i]
    if (entry.isElse) return false
    entry.guard?.let { if (condGuarantees(it, true, g)) return true }
    val conds = entry.conditions
    if (conds.isEmpty()) return false
    val subject = whenSubjectValue(w)
    if (subject == null) {
        return conds.all { c -> c is KtWhenConditionWithExpression && condGuarantees(c.expression, true, g) }
    }
    // `when (true) { (s != null) -> … }` and `when (s != null) { true -> … }`: a Boolean matched against a literal.
    booleanEntry(subject, conds, matched = true, g)?.let { if (it) return true }
    if (!carriesValue(subject, g) && !subjectVariableIs(w, g)) return false
    // The subject matched `is T`, or (for non-null) equals a non-null constant.
    return conds.all { c ->
        when (c) {
            is KtWhenConditionIsPattern -> !c.isNegated && g.target(c.typeReference)
            is KtWhenConditionWithExpression ->
                if (g.provesNull) isNullValue(c.expression) else g.nonNull && isNonNullLiteral(c.expression)
            else -> false
        }
    }
}

/**
 * A `when` over a Boolean against a Boolean: the subject or the condition is a `true`/`false` literal and the other
 * side a condition. [matched] says whether the branch was taken. Null when this is not that shape.
 */
private fun KotlinResolver.booleanEntry(subject: KtExpression?, conds: List<dev.ide.kotlin.syntax.psi.KtWhenCondition>, matched: Boolean, g: Goal): Boolean? {
    val c = conds.singleOrNull() as? KtWhenConditionWithExpression ?: return null
    fun lit(e: KtExpression?) = (stripAll(e) as? KtConstantExpression)?.text?.takeIf { it == "true" || it == "false" }
    val subjectLit = lit(subject)
    val condLit = lit(c.expression)
    return when {
        subjectLit != null && condLit == null -> condGuarantees(c.expression, (subjectLit == "true") == matched, g)
        condLit != null && subjectLit == null -> condGuarantees(subject, (condLit == "true") == matched, g)
        else -> null
    }
}

private fun subjectVariableIs(w: KtWhenExpression, g: Goal): Boolean = g.name != null && w.subjectVariable?.name == g.name

/** Whether one of the first [upTo] entries of [w] having FAILED proves the value: a subjectless condition that
 *  is false only for a non-null value, or, on a `when` over the value, a `null` or `!is T` branch passed over. */
private fun KotlinResolver.whenPrefixFalseProves(w: KtWhenExpression, upTo: Int, g: Goal): Boolean {
    val subject = whenSubjectValue(w)
    val onValue = subject != null && (carriesValue(subject, g) || subjectVariableIs(w, g))
    for (entry in w.entries.take(upTo)) {
        // With a guard, a failed entry may have had its conditions hold; nothing follows from it.
        if (entry.isElse || entry.guard != null) continue
        for (c in entry.conditions) {
            val proves = when {
                subject == null -> c is KtWhenConditionWithExpression && condGuarantees(c.expression, false, g)
                booleanEntry(subject, listOf(c), matched = false, g) == true -> true
                !onValue -> false
                c is KtWhenConditionWithExpression -> g.nonNull && isNullLit(stripAll(c.expression))
                c is KtWhenConditionIsPattern -> c.isNegated && g.target(c.typeReference)
                else -> false
            }
            if (proves) return true
        }
    }
    return false
}

/**
 * Whether every `break` (or, with [continues], every `continue`) that leaves [loop] is reached only with the value
 * proven non-null, judged by the same walk from the jump up to the loop. A jump inside a lambda (a non-local
 * `break` out of an inline lambda) is not judged, and fails the proof.
 */
private fun KotlinResolver.loopJumpsProven(
    loop: KtLoopExpression, g: Goal, rootName: String?, continues: Boolean,
): Boolean {
    val label = (loop.parent as? KtLabeledExpression)?.getLabelName()
    var ok = true
    fun visit(p: KtElement, nested: Boolean, inLambda: Boolean) {
        if (!ok) return
        when (p) {
            is KtNamedFunction, is KtClassOrObject -> return
            is KtBreakExpression, is KtContinueExpression -> {
                val wanted = if (continues) p is KtContinueExpression else p is KtBreakExpression
                val l = (p as KtExpressionWithLabel).getLabelName()
                val targetsLoop = if (l != null) l == label else !nested
                if (wanted && targetsLoop) {
                    if (inLambda || !flowProvenAt(p, p.textRange.startOffset, g, rootName, stopAt = loop)) ok = false
                }
                return
            }
        }
        var c = p.firstChild
        while (c != null) {
            visit(c, nested || c is KtLoopExpression, inLambda || c is KtFunctionLiteral)
            c = c.nextSibling
        }
    }
    loop.body?.let { visit(it, it is KtLoopExpression, false) }
    // A condition may hold a jump too (`while (x ?: break)`).
    if (loop is KtWhileExpressionBase) loop.condition?.let { visit(it, it is KtLoopExpression, false) }
    return ok
}

/** A statement that plainly never completes: a jump, a `while (true)` / `do … while (true)` without a `break`, or a
 *  `try` whose `finally` (or both of whose `if` branches) does not. */
private fun neverCompletes(st: KtExpression?): Boolean = when (val x = stripAll(st)) {
    is KtReturnExpression, is KtThrowExpression, is KtBreakExpression, is KtContinueExpression -> true
    is KtWhileExpressionBase -> isTrueLiteral(x.condition) && !hasBreakFor(x)
    is KtBlockExpression -> x.statements.any { neverCompletes(it) }
    is KtTryExpression -> neverCompletes(x.finallyBlock?.finalExpression) ||
        (neverCompletes(x.tryBlock) && x.catchClauses.all { neverCompletes(it.catchBody) })
    is KtIfExpression -> x.`else` != null && neverCompletes(x.then) && neverCompletes(x.`else`)
    else -> false
}

/** Whether a `break` (unlabeled and not inside a nested loop, or labeled with [loop]'s label) leaves [loop]. */
private fun hasBreakFor(loop: KtLoopExpression): Boolean {
    val label = (loop.parent as? KtLabeledExpression)?.getLabelName()
    var found = false
    fun visit(p: KtElement, nested: Boolean) {
        if (found || p is KtNamedFunction || p is KtClassOrObject) return
        if (p is KtBreakExpression) {
            val l = p.getLabelName()
            if (if (l != null) l == label else !nested) found = true
            return
        }
        var c = p.firstChild
        while (c != null) { visit(c, nested || c is KtLoopExpression); c = c.nextSibling }
    }
    loop.body?.let { visit(it, it is KtLoopExpression) }
    return found
}

private fun safeTarget(assignment: KtBinaryExpression): Boolean = stripAll(assignment.left) is KtSafeQualifiedExpression

/** A value that cannot be null whatever is in it: `v!!`, a non-null literal, a string. */
private fun obviouslyNonNull(e: KtExpression?): Boolean = when (val s = stripAll(e)) {
    is KtPostfixExpression -> s.operationToken == KtTokens.EXCLEXCL
    else -> isNonNullLiteral(s)
}

private fun isTrueLiteral(e: KtExpression?): Boolean = (stripAll(e) as? KtConstantExpression)?.text == "true"

/** A literal that is not `null`: a number, a character, a Boolean, or a string. */
private fun isNonNullLiteral(e: KtElement?): Boolean = when (val s = stripAll(e)) {
    is KtConstantExpression -> s.text != "null"
    is KtStringTemplateExpression -> true
    else -> false
}

/**
 * Whether [e] evaluating to a non-null value proves the goal: for non-null, it IS the value; it is a safe chain or
 * `!!`/`as`/`as?` over it (`v?.a?.b` is null whenever `v` is); or it is a `val` local whose initializer does
 * (`val t = v?.a; if (t != null) v.use`). For a type, it is `v as? T` (or a safe chain hanging off one).
 */
private fun valueProves(e: KtExpression?, g: Goal, depth: Int = 0): Boolean {
    if (depth > 8) return false
    val x = stripAll(e) as? KtExpression ?: return false
    if (g.nonNull && g.matches(x)) return true
    return when (x) {
        is KtSafeQualifiedExpression -> valueProves(x.receiverExpression, g, depth + 1)
        is KtPostfixExpression -> x.operationToken == KtTokens.EXCLEXCL && valueProves(x.baseExpression, g, depth + 1)
        is KtBinaryExpressionWithTypeRHS ->
            (!g.nonNull && g.target(x.right) && g.sameValue(x.left)) || valueProves(x.left, g, depth + 1)
        is KtNameReferenceExpression -> localValInitializer(x)?.let { valueProves(it, g, depth + 1) } == true
        else -> false
    }
}

/** The subject of an `is`/`as`/`when` test that speaks for the goal: for non-null, anything whose non-null value
 *  proves it (`v?.a is T` proves `v`); for a type, the value itself. */
private fun carriesValue(e: KtExpression?, g: Goal): Boolean = if (g.nonNull) valueProves(e, g) else g.sameValue(e)

/** `e is T` holding (or `e as T` completing) proves the goal. */
private fun isCheckProves(e: KtExpression?, target: KtTypeReference?, g: Goal): Boolean =
    g.target(target) && carriesValue(e, g)

/** The initializer of the local `val` (no delegate) [ref] reads, declared before it in an enclosing block. Unless
 *  [typed], a `val` with a declared type does not count: that makes it a value of its own, not an alias Kotlin
 *  follows (`val a2: String? = a1` is not narrowed by a check on `a1`). A Boolean holding a condition is followed
 *  either way. */
internal fun localValInitializer(ref: KtNameReferenceExpression, typed: Boolean = false): KtExpression? {
    val name = ref.getReferencedName()
    val offset = ref.textRange.startOffset
    var node: KtElement? = ref.parent
    while (node != null) {
        when (node) {
            is KtBlockExpression -> {
                val local = node.statements.lastOrNull {
                    it is KtProperty && it.name == name && it.textRange.endOffset <= offset
                } as? KtProperty
                if (local != null) {
                    return if (local.isVar || local.hasDelegate() || (!typed && local.typeReference != null)) null
                    else local.initializer
                }
            }
            is KtFunction -> if (node.valueParameters.any { it.name == name }) return null
            is KtClassOrObject -> return null
        }
        node = node.parent
    }
    return null
}

/** Whether [cond], evaluating to [whenTrue], guarantees the matched value is non-null: `v != null` (true side) /
 *  `v == null` (false side), `v == <non-null literal>`, an `is`/negation, and `&&` (true side) / `||` (false
 *  side) conjunctions. */
private fun KotlinResolver.condGuarantees(
    cond: KtExpression?, whenTrue: Boolean, g: Goal
): Boolean = condGuaranteesByValue(cond, whenTrue, g) || completionProves(cond, g)

/** [condGuarantees] from what the condition's VALUE says. That it has a value at all says the rest: it was
 *  evaluated, so whatever completing it proves holds too, which is how `a && (x as T).y == null` being true
 *  makes `x` a `T` (the right side ran). */
private fun KotlinResolver.condGuaranteesByValue(
    cond: KtExpression?, whenTrue: Boolean, g: Goal
): Boolean = when (val c = unwrapParens(cond)) {
    is KtBinaryExpression -> when (c.operationToken) {
        KtTokens.EXCLEQ, KtTokens.EXCLEQEQEQ ->
            if (whenTrue) isNullCmp(c, g) else equalsNonNull(c, g) || equalsNull(c, g)
        KtTokens.EQEQ, KtTokens.EQEQEQ ->
            if (whenTrue) equalsNonNull(c, g) || equalsNull(c, g) else isNullCmp(c, g)
        // `a && b` true has both true; false has one of them false, so both must prove it (a constant side that
        // cannot be the one does not need to). `||` the other way round.
        KtTokens.ANDAND -> if (whenTrue) condGuarantees(c.left, true, g) || condGuarantees(c.right, true, g)
        else bothSidesProve(c, false, g)

        KtTokens.OROR -> if (!whenTrue) condGuarantees(c.left, false, g) || condGuarantees(c.right, false, g)
        else bothSidesProve(c, true, g)

        // `a?.b ?: return` / `a?.b ?: false` as a condition: its value came from a non-null left, or from the
        // right. Each way has to prove the goal; a right that jumps, or is the opposite literal, never gives it.
        KtTokens.ELVIS -> (valueProves(c.left, g) || condGuarantees(c.left, whenTrue, g)) &&
            (condGuarantees(c.right, whenTrue, g) || completionProves(c.right, g) ||
                (stripAll(c.right) as? KtConstantExpression)?.text == (!whenTrue).toString())

        // An infix call to a project function whose contract speaks for its result (`b takeRight (x is String)`).
        KtTokens.IDENTIFIER -> g.deep && infixContractGuarantees(c, whenTrue, g)

        else -> false
    }
    // `v is T` (a non-nullable target) implies non-null on the true side (`!is` flips it).
    is KtIsExpression -> whenTrue != c.isNegated && isCheckProves(c.leftHandSide, c.typeReference, g)

    is KtPrefixExpression -> if (c.operationToken == KtTokens.EXCL) condGuarantees(
        c.baseExpression, !whenTrue, g
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
    is KtDotQualifiedExpression -> (!whenTrue && g.nonNull && isNullOrEmptyGuard(c, g)) || (g.deep &&
        (c.selectorExpression as? KtCallExpression)?.let { contractGuarantees(it, c.receiverExpression, whenTrue, g) } == true)

    // A project function whose `contract { returns(true) implies (s != null) }` speaks for its result.
    is KtCallExpression -> g.deep && contractGuarantees(c, null, whenTrue, g)

    // A Boolean `val` holding a condition (`val ok = a != null; if (ok) a.length`) says what the condition does.
    is KtNameReferenceExpression -> localValInitializer(c, typed = true)?.let { init ->
        stripAll(init) !is KtNameReferenceExpression && condGuarantees(init, whenTrue, g)
    } == true

    else -> false
}

/** Each side of [c] either proves the goal when it evaluates to [side], or is the literal that never does. */
private fun KotlinResolver.bothSidesProve(c: KtBinaryExpression, side: Boolean, g: Goal): Boolean {
    fun one(e: KtExpression?) = (stripAll(e) as? KtConstantExpression)?.text == (!side).toString() || condGuarantees(e, side, g)
    return one(c.left) && one(c.right)
}

/**
 * `a == <non-null literal>` holding (or `a != <literal>` failing): `a` equals a non-null value, so it is non-null,
 * and so is every value a safe chain in it hangs off (`v?.isEmpty() == true` proves `v`). Against a Boolean
 * literal, the other side also says what a condition being true or false does (`true == (v != null)`).
 */
private fun KotlinResolver.equalsNonNull(binary: KtBinaryExpression, g: Goal): Boolean {
    val l = stripAll(binary.left) as? KtExpression
    val r = stripAll(binary.right) as? KtExpression
    val (lit, other) = when {
        isNonNullLiteral(r) -> r to l
        isNonNullLiteral(l) -> l to r
        // `a?.b == X.Y` with a non-null `X.Y`: equal to a non-null value, the left is not null either. A comparand
        // whose type is not known at all (its library is not on the path) is not taken for a nullable one: what
        // is wrong there is the unresolved name, which is reported as such.
        else -> {
            if (!g.deep) return false
            fun nonNull(e: KtExpression?): Boolean {
                if (e == null || (stripAll(e) as? KtConstantExpression)?.text == "null") return false
                val t = inferType(e) ?: return true
                if (!t.isTypeParameter && !service.isKnownType(canonicalTypeForCheck(t).qualifiedName)) return true
                return !t.nullable && !t.isTypeParameter && t.qualifiedName != "kotlin.Nothing"
            }
            return (nonNull(r) && valueProves(l, g)) || (nonNull(l) && valueProves(r, g))
        }
    }
    if (valueProves(other, g)) return true
    return when (lit?.text) {
        "true" -> condGuarantees(other, true, g)
        "false" -> condGuarantees(other, false, g)
        else -> false
    }
}

/**
 * Whether a call to a SOURCE function guarantees the matched value non-null through the function's own contract:
 * `fun isValid(s: String?): Boolean { contract { returns(true) implies (s != null) }; … }` makes
 * `if (isValid(x)) x.length` safe, as the compiler's contract reader does. The `implies` condition is judged by
 * [condGuarantees] with each of the function's parameters standing for the argument passed to it, and `this` for
 * [receiver] (an extension's receiver). Only a `returns(true|false)`, a bare `returns()` and `returnsNotNull()`
 * effect are read, and only from the first statement of a block body, which is where Kotlin requires the
 * `contract { }` call. A library function's contract is not in reach (it lives in @Metadata), so it says nothing.
 */
private fun KotlinResolver.contractGuarantees(
    call: KtCallExpression,
    receiver: KtExpression?,
    whenTrue: Boolean,
    g: Goal,
    completion: Boolean = false,
): Boolean {
    val callee = runCatching { resolveCalleeFunction(call) }.getOrNull() ?: return false
    val args = call.valueArguments
    return contractGuaranteesOf(callee, receiver, whenTrue, g, completion, call) { params, name ->
        val named = args.firstOrNull { it.getArgumentName()?.asName?.identifier == name }
        val i = params.indexOf(name)
        val arg = named ?: args.getOrNull(i)?.takeIf { it.getArgumentName() == null && i >= 0 }
        arg?.getArgumentExpression()
    }
}

/** [contractGuarantees] for an infix call `left name right`: the receiver is `left`, the one argument `right`. */
private fun KotlinResolver.infixContractGuarantees(c: KtBinaryExpression, whenTrue: Boolean, g: Goal): Boolean {
    if (c.operationToken != KtTokens.IDENTIFIER) return false
    val callee = runCatching { resolveInfixFunction(c) }.getOrNull() ?: return false
    return contractGuaranteesOf(callee, c.left, whenTrue, g, completion = false) { params, name ->
        c.right.takeIf { params.indexOf(name) == 0 }
    }
}

private fun KotlinResolver.contractGuaranteesOf(
    callee: dev.ide.lang.kotlin.symbols.KotlinSymbol,
    receiver: KtExpression?,
    whenTrue: Boolean,
    g: Goal,
    completion: Boolean,
    call: KtCallExpression? = null,
    argumentFor: (params: List<String?>, name: String) -> KtExpression?,
): Boolean {
    if (!callee.origin.fromSource) {
        return binaryContractGuarantees(callee, receiver, whenTrue, g, completion, call, argumentFor)
    }
    val fn = (callee.declaration() as? dev.ide.lang.kotlin.parse.KotlinDomNode)?.psi
        as? dev.ide.kotlin.syntax.psi.KtNamedFunction ?: return false
    val first = fn.bodyBlockExpression?.statements?.firstOrNull() as? KtCallExpression ?: return false
    if ((first.calleeExpression as? KtNameReferenceExpression)?.getReferencedName() != "contract") return false
    val effects = first.lambdaArguments.firstOrNull()?.getLambdaExpression()?.bodyExpression?.statements.orEmpty()
    val params = fn.valueParameters.map { it.name }
    // Each contract expression names a parameter (or `this`); it matches when the argument passed for it does.
    val argMatches: Matcher = { e ->
        when (val x = unwrapParens(e)) {
            is dev.ide.kotlin.syntax.psi.KtThisExpression -> receiver != null && carriesValue(receiver, g)
            is KtNameReferenceExpression -> argumentFor(params, x.getReferencedName())?.let { carriesValue(it, g) } == true
            else -> false
        }
    }
    val inner = Goal(argMatches, g.type, deep = false, provesNull = g.provesNull)
    for (effect in effects) {
        val implies = effect as? KtBinaryExpression ?: continue
        if (implies.operationReference.getReferencedName() != "implies") continue
        val returns = implies.left as? KtCallExpression ?: continue
        val holds = when ((returns.calleeExpression as? KtNameReferenceExpression)?.getReferencedName()) {
            // [completion] asks only "did the call return?", which a bare `returns()` answers.
            "returns" -> when (returns.valueArguments.singleOrNull()?.getArgumentExpression()?.text) {
                "true" -> whenTrue && !completion
                "false" -> !whenTrue && !completion
                null -> returns.valueArguments.isEmpty()
                else -> false
            }
            else -> false
        }
        if (!holds) continue
        if (condGuarantees(implies.right, true, inner)) return true
        // `implies cond` on a Boolean parameter: what the argument passed for it guarantees when true
        // (`b takeRight (x is String)` makes `x` a String).
        (unwrapParens(implies.right) as? KtNameReferenceExpression)?.getReferencedName()
            ?.takeIf { it in params }
            ?.let { argumentFor(params, it) }
            ?.let { if (condGuarantees(it, true, g)) return true }
    }
    return false
}

/**
 * [contractGuaranteesOf] for a LIBRARY function: its contract comes from @Metadata ([KotlinSymbol.contract]),
 * already parsed into conditions on parameter positions (0 the receiver, i the i-th value parameter). The
 * effects read are the ones a source contract's are: `returns()`, `returns(true|false)`, each implying a
 * condition judged against the argument passed at that position. kotlin.test's `assertNotNull(x)`
 * (`returns() implies (actual != null)`) and `assertTrue(x is T)` (`returns() implies actual`) are the shapes
 * this exists for.
 */
private fun KotlinResolver.binaryContractGuarantees(
    callee: dev.ide.lang.kotlin.symbols.KotlinSymbol,
    receiver: KtExpression?,
    whenTrue: Boolean,
    g: Goal,
    completion: Boolean,
    call: KtCallExpression?,
    argumentFor: (params: List<String?>, name: String) -> KtExpression?,
): Boolean {
    val text = callee.contract ?: return false
    val params: List<String?> = callee.paramNames
    fun arg(i: Int): KtExpression? =
        if (i == 0) receiver else params.getOrNull(i - 1)?.let { argumentFor(params, it) }

    fun proves(e: KotlinContractExpression): Boolean = when (e) {
        is KotlinContractExpression.NullCheck ->
            if (e.negated) g.nonNull && carriesValue(arg(e.parameter), g)
            else g.provesNull && g.sameValue(arg(e.parameter))

        is KotlinContractExpression.IsInstance -> !e.negated && carriesValue(arg(e.parameter), g) && run {
            // A type parameter's target is what the call passes for it (`assertIs<String>(x)`); one left to
            // inference is not known here.
            val target = if (e.isTypeParameter) {
                val i = callee.typeParameters.indexOf(e.typeName)
                call?.typeArguments?.getOrNull(i)?.typeReference?.text ?: return@run false
            } else {
                e.typeName + if (e.nullable) "?" else ""
            }
            when {
                g.provesNull -> false
                g.type == null -> isNonNullIsTarget(target)
                else -> {
                    val erased = erasedTypeText(target)
                    erased == g.type || (!e.isTypeParameter && erased.endsWith("." + g.type))
                }
            }
        }

        // `returns() implies value`: what the argument passed for the Boolean guarantees when it is true.
        is KotlinContractExpression.BooleanParameter -> condGuarantees(arg(e.parameter), !e.negated, g)
        is KotlinContractExpression.And -> e.operands.any(::proves)
        is KotlinContractExpression.Or -> e.operands.all(::proves)
        is KotlinContractExpression.Constant -> false
    }

    for (effect in KotlinContractCodec.decode(text)) {
        if (effect.kind != KotlinContractEffect.Kind.RETURNS) continue
        val holds = when (effect.returns) {
            null -> true
            KotlinContractConstant.TRUE -> whenTrue && !completion
            KotlinContractConstant.FALSE -> !whenTrue && !completion
            KotlinContractConstant.NULL -> false
        }
        if (holds && effect.conclusion?.let(::proves) == true) return true
    }
    return false
}

/** Whether [e], or any expression evaluated within it (lambda bodies aside), carries the matched value: the
 *  gate a call's arguments pass before its contract is worth resolving (`assertTrue(x is T)` speaks for `x`). */
private fun mentionsValue(e: KtElement?, g: Goal, depth: Int = 0): Boolean {
    if (e == null || depth > 12) return false
    if (e is KtExpression && carriesValue(e, g)) return true
    if (e is dev.ide.kotlin.syntax.psi.KtLambdaExpression) return false
    // A Boolean `val` holding a condition stands for it (`val ok = x is T; assertTrue(ok)`).
    if (e is KtNameReferenceExpression) return localValInitializer(e, typed = true)?.let { mentionsValue(it, g, depth + 1) } == true
    var c = e.firstChild
    while (c != null) {
        if (mentionsValue(c, g, depth + 1)) return true
        c = c.nextSibling
    }
    return false
}

/** `v.isNullOrBlank()` / `v.isNullOrEmpty()` on the matched value — false implies non-null (their contract). */
private fun KotlinResolver.isNullOrEmptyGuard(c: KtDotQualifiedExpression, g: Goal): Boolean {
    val call = c.selectorExpression as? KtCallExpression ?: return false
    if (call.valueArguments.isNotEmpty()) return false
    val name = (call.calleeExpression as? KtNameReferenceExpression)?.getReferencedName()
    if (name != "isNullOrBlank" && name != "isNullOrEmpty") return false
    return valueProves(c.receiverExpression, g)
}


/** Whether [binary] is `v == null` / `v != null` (either operand order) for the matched value. */
private fun KotlinResolver.isNullCmp(
    binary: KtBinaryExpression,
    g: Goal
): Boolean {
    val l = stripParens(binary.left)
    val r = stripParens(binary.right)
    val operand = when {
        isNullValue(r) -> l
        isNullValue(l) -> r
        else -> return false
    }
    return g.nonNull && valueProves(operand as? KtExpression, g)
}

/** `v == null` holding, for a goal that the value IS null. */
private fun equalsNull(binary: KtBinaryExpression, g: Goal): Boolean {
    if (!g.provesNull) return false
    val l = binary.left
    val r = binary.right
    return (isNullValue(r) && g.sameValue(l)) || (isNullValue(l) && g.sameValue(r))
}

/** `null`, or a `val` that holds it (`val x: Nothing? = null`): comparing with it is a null check. */
private fun isNullValue(e: KtElement?, depth: Int = 0): Boolean = when (val s = stripAll(e)) {
    is KtConstantExpression -> s.text == "null"
    is KtNameReferenceExpression -> depth < 4 && localValInitializer(s, typed = true)?.let { isNullValue(it, depth + 1) } == true
    else -> false
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

/** Unwrap parentheses, labels and annotations: `(x)`, `l@ x`, `@A x` all evaluate to `x`. */
internal fun stripAll(e: KtElement?): KtElement? {
    var cur = e
    while (true) {
        cur = when (cur) {
            is KtParenthesizedExpression -> cur.expression
            is KtLabeledExpression -> cur.baseExpression
            is KtAnnotatedExpression -> cur.baseExpression
            else -> return cur
        }
    }
}
