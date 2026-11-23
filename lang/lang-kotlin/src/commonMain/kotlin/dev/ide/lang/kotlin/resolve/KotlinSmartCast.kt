package dev.ide.lang.kotlin.resolve

import dev.ide.kotlin.syntax.psi.KtProperty
import dev.ide.kotlin.syntax.psi.KtClassOrObject
import dev.ide.kotlin.syntax.psi.KtNamedFunction
import dev.ide.kotlin.syntax.psi.KtLambdaExpression
import dev.ide.kotlin.syntax.psi.KtUnaryExpression
import dev.ide.kotlin.syntax.psi.KtBinaryExpression
import dev.ide.kotlin.syntax.psi.KtBinaryExpressionWithTypeRHS
import dev.ide.kotlin.syntax.psi.KtBlockExpression
import dev.ide.kotlin.syntax.psi.KtCallExpression
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
    val own = smartCastTypeAtOwn(name, offset)
    // K2's bound smart casts: a `val` every path assigned from one stable name is that value, so a check on either
    // narrows both (`if (b) x = a else x = a; a as A; x.s`). The narrower of the two wins.
    val viaAlias = aliasPartner(name, offset)?.let { smartCastTypeAtOwn(it, offset) } ?: return own
    if (own == null) return viaAlias
    return if (viaAlias.qualifiedName != own.qualifiedName && own.isAssignableFrom(viaAlias)) viaAlias else own
}

/**
 * The name [name] is an alias of at [offset], or that is an alias of it: a local `val` declared without an
 * initializer (or initialized with a plain name), every write of which before [offset] is `x = a` with the same
 * stable local `a`. Null when there is none.
 */
private fun KotlinResolver.aliasPartner(name: String, offset: Int): String? {
    if (name == THIS_SUBJECT || name.contains('.') || name.contains('@')) return null
    // Cheap gate: the names any candidate `val` declares or is initialized from, plus every name assigned to one.
    val candidates = caches.aliasCandidateNames ?: HashSet<String>().also { set ->
        fun visit(e: KtElement) {
            if (e is dev.ide.kotlin.syntax.psi.KtProperty && !e.isVar && e.isLocal) {
                val init = e.initializer?.let { unwrapParens(it) }
                if (init == null || init is KtNameReferenceExpression) {
                    e.name?.let(set::add)
                    (init as? KtNameReferenceExpression)?.getReferencedName()?.let(set::add)
                }
            }
            if (e is KtBinaryExpression && e.operationToken == KtTokens.EQ) (unwrapParens(e.right) as? KtNameReferenceExpression)?.getReferencedName()?.let(set::add)
            var c = e.firstChild
            while (c != null) { (c as? KtElement)?.let(::visit); c = c.nextSibling }
        }
        visit(ktFile)
        caches.aliasCandidateNames = set
    }
    if (name !in candidates) return null
    val block = elementAt(offset)?.let { var n: KtElement? = it; while (n != null && n !is KtBlockExpression) n = n.parent as? KtElement; n } as? KtBlockExpression
        ?: return null
    // Walk out through the enclosing blocks for `val` declarations before [offset].
    var b: KtElement? = block
    while (b != null) {
        if (b is KtBlockExpression) {
            for (st in b.statements) {
                if (st.textRange.startOffset >= offset) break
                val p = st as? dev.ide.kotlin.syntax.psi.KtProperty ?: continue
                if (p.isVar || p.hasDelegate()) continue
                val v = p.name ?: continue
                val source = aliasSource(b, p, offset) ?: continue
                if (v == name && source != name) return source
                if (source == name && v != name) return v
            }
        }
        if (b is dev.ide.kotlin.syntax.psi.KtDeclaration && b !is dev.ide.kotlin.syntax.psi.KtProperty) break
        b = b.parent as? KtElement
    }
    return null
}

/** The one stable name every write of `val` [p] in [block] before [offset] assigns it (its initializer, or the
 *  deferred `p = a` in each branch), or null. */
private fun KotlinResolver.aliasSource(block: KtBlockExpression, p: dev.ide.kotlin.syntax.psi.KtProperty, offset: Int): String? {
    val v = p.name ?: return null
    // `val a2: Any = a1` is a value of its declared type, not an alias (K2 binds only an untyped or deferred `val`).
    if (p.initializer != null && p.typeReference != null) return null
    var source: String? = (p.initializer?.let { unwrapParens(it) } as? KtNameReferenceExpression)?.getReferencedName()
    if (p.initializer != null && source == null) return null
    var writes = 0
    fun visit(e: KtElement) {
        if (e.textRange.startOffset >= offset) return
        if (e is KtBinaryExpression && e.operationToken == KtTokens.EQ &&
            (unwrapParens(e.left) as? KtNameReferenceExpression)?.getReferencedName() == v
        ) {
            writes++
            val rhs = (e.right?.let { unwrapParens(it) } as? KtNameReferenceExpression)?.getReferencedName()
            source = if (rhs == null || (source != null && source != rhs)) "\u0000" else rhs
        }
        var c = e.firstChild
        while (c != null) { (c as? KtElement)?.let(::visit); c = c.nextSibling }
    }
    block.statements.dropWhile { it !== p }.drop(1).forEach(::visit)
    val s = source ?: return null
    if (s == "\u0000" || (p.initializer == null && writes == 0)) return null
    // The source must itself be a stable value: a `val` or a parameter, never written.
    val decl = localsAt(offset, s).firstOrNull() ?: return null
    if (decl.kind != dev.ide.lang.resolve.SymbolKind.PARAMETER && decl.kind != dev.ide.lang.resolve.SymbolKind.LOCAL_VARIABLE) return null
    val ownDecl = block.statements.firstOrNull { it is dev.ide.kotlin.syntax.psi.KtProperty && it.name == s } as? dev.ide.kotlin.syntax.psi.KtProperty
    if (ownDecl?.isVar == true) return null
    if (writtenNames(block).written.contains(s)) return null
    return s
}

private fun KotlinResolver.smartCastTypeAtOwn(name: String, offset: Int): KotlinType? {
    var child: KtElement? = null
    val start = elementAt(offset)
    var node: KtElement? = start
    while (node != null) {
        narrowingAtNode(node, child, offset, name)?.let {
            // Established OUTSIDE a loop (or a lambda called in place again and again) that writes the name: the
            // back edge brings the written value round to this read, so K2's data flow merges the narrowing away
            // at the loop's entry (FirDataFlowAnalyzer's loop-entry reset of reassigned variables).
            if (rewrittenBetween(start, node, name)) return null
            // A declaration of the same name between the check and the use shadows the checked variable
            // (`when (val y = x) { is String -> { val y = y.length; useInt(y) } }`): the use reads the new one.
            if (shadowedBetween(start, node, name)) return null
            // A plain write of the variable between the check and the use replaces the value the check was about
            // (`if (b is DefaultImpl) { b = makeOther(); b.x }`): what holds now is the flow's to say.
            if (assignedBetween(start, node, name)) return flowNarrowing(name, offset)
            return keepDeclaredWhenNarrower(it, name, offset)
        }
        child = node
        node = node.parent
    }
    return flowNarrowing(name, offset)
}

/** Whether a block strictly between [from] and its ancestor [to] has a statement `name = ...` before the path to [from]. */
private fun KotlinResolver.assignedBetween(from: KtElement?, to: KtElement, name: String): Boolean {
    if ('.' in name || name == THIS_SUBJECT) return false
    var child: KtElement? = null
    var n = from
    while (n != null && n !== to) {
        if (n is KtBlockExpression && child != null) {
            for (st in n.statements) {
                if (st === child) break
                if (st is KtBinaryExpression && st.operationToken == KtTokens.EQ &&
                    (unwrapParens(st.left) as? KtNameReferenceExpression)?.getReferencedName() == name
                ) return true
            }
        }
        child = n
        n = n.parent
    }
    return false
}

/** Whether a block strictly between [from] and its ancestor [to] declares a local [name] before the path to [from]. */
private fun shadowedBetween(from: KtElement?, to: KtElement, name: String): Boolean {
    if ('.' in name || name == THIS_SUBJECT) return false
    var child: KtElement? = null
    var n = from
    while (n != null && n !== to) {
        if (n is KtBlockExpression && child != null) {
            for (st in n.statements) {
                if (st === child) break
                if ((st as? dev.ide.kotlin.syntax.psi.KtProperty)?.name == name) return true
            }
        }
        child = n
        n = n.parent
    }
    return false
}

/** Whether a node strictly between [from] and its ancestor [to] runs again and writes [name] ([rewrittenOnBackEdge]). */
private fun KotlinResolver.rewrittenBetween(from: KtElement?, to: KtElement, name: String): Boolean {
    var n = from
    while (n != null && n !== to) {
        if (rewrittenOnBackEdge(n, name)) return true
        n = n.parent
    }
    return false
}

/**
 * Whether [node] runs its body again (a loop, or a lambda an `AT_LEAST_ONCE` contract calls in place) and that body
 * assigns [name]. The written names are collected once per node per snapshot ([writtenNames]); the in-place kind of
 * a lambda is asked only once its body is known to write [name].
 */
internal fun KotlinResolver.rewrittenOnBackEdge(node: KtElement, name: String): Boolean {
    if (node !is dev.ide.kotlin.syntax.psi.KtLoopExpression && node !is dev.ide.kotlin.syntax.psi.KtLambdaExpression) return false
    val w = writtenNames(node)
    if (name !in w.written || name in w.declared) return false
    if (node is dev.ide.kotlin.syntax.psi.KtLambdaExpression) return KotlinVarNullFlow(this).inPlaceKind(node) == InPlace.AT_LEAST_ONCE
    return true
}

/** The names assigned under an element (`=`, a compound assignment, `++`/`--`), not counting a nested lambda, local
 *  function or class, and the names declared under it (whose writes may be to that declaration instead). */
internal class WrittenNames(val written: Set<String>, val declared: Set<String>)

private val NO_WRITES = WrittenNames(emptySet(), emptySet())

/** [WrittenNames] of [root], memoized per snapshot in [KotlinResolverCaches.writtenNames]. */
internal fun KotlinResolver.writtenNames(root: KtElement): WrittenNames = caches.writtenNames.getOrPut(root) {
    var written: HashSet<String>? = null
    var declared: HashSet<String>? = null
    fun visit(e: KtElement) {
        if (e !== root && (e is dev.ide.kotlin.syntax.psi.KtLambdaExpression || e is dev.ide.kotlin.syntax.psi.KtClassOrObject ||
                e is dev.ide.kotlin.syntax.psi.KtNamedFunction)) return
        when (e) {
            is KtBinaryExpression -> if (e.operationToken == KtTokens.EQ || e.operationToken in WRITE_OPS)
                (unwrapParens(e.left) as? KtNameReferenceExpression)?.let { (written ?: HashSet<String>().also { h -> written = h }) += it.getReferencedName() }
            is dev.ide.kotlin.syntax.psi.KtUnaryExpression -> if (e.operationToken == KtTokens.PLUSPLUS || e.operationToken == KtTokens.MINUSMINUS)
                (unwrapParens(e.baseExpression) as? KtNameReferenceExpression)?.let { (written ?: HashSet<String>().also { h -> written = h }) += it.getReferencedName() }
            is dev.ide.kotlin.syntax.psi.KtProperty -> e.name?.let { (declared ?: HashSet<String>().also { h -> declared = h }) += it }
        }
        var c = e.firstChild
        while (c != null) { (c as? KtElement)?.let(::visit); c = c.nextSibling }
    }
    visit(root)
    if (written == null) NO_WRITES else WrittenNames(written!!, declared ?: emptySet())
}

private val WRITE_OPS = setOf(KtTokens.PLUSEQ, KtTokens.MINUSEQ, KtTokens.MULTEQ, KtTokens.DIVEQ, KtTokens.PERCEQ)

/**
 * A smart cast to a type the declaration already implies narrows nothing: the value is the intersection of the
 * two, which is the declared type itself (`a: A<T>` checked `is Any` is still an `A<T>`). Returning the check's own type there loses members and type
 * arguments the declaration had. [narrowed] is returned as is when it is genuinely narrower (or unrelated).
 */
private fun KotlinResolver.keepDeclaredWhenNarrower(narrowed: KotlinType, name: String, offset: Int): KotlinType? {
    if (name == THIS_SUBJECT) return narrowed
    val declared = localsAt(offset, name).firstOrNull()?.type as? KotlinType ?: return narrowed
    if (declared.isTypeParameter || narrowed.isTypeParameter) return narrowed
    // The same classifier is left to the check: its type arguments may say something new (`a as G<Int>` on a
    // `G<Number>`), which the erased narrowing cannot carry but the declaration would contradict.
    if (narrowed.qualifiedName == declared.qualifiedName) return narrowed
    val nonNull = declared.withNullable(false)
    if (narrowed.isAssignableFrom(nonNull)) return nonNull
    // Two unrelated types (`a: A` checked `is B`): the value is both, `B & A`, so it keeps the declared type's
    // members and assignability. Only for two known classifiers with no type arguments to reconcile.
    if (!nonNull.isAssignableFrom(narrowed) && nonNull.typeArguments.isEmpty() && narrowed.intersection.isEmpty() &&
        service.isKnownType(nonNull.qualifiedName) && service.isKnownType(narrowed.qualifiedName) &&
        nonNull.qualifiedName != "kotlin.Any"
    ) return narrowed.withIntersection(listOf(nonNull))
    return narrowed
}

/**
 * The narrowing the flow walk ([flowProvenTypes]) proves for the name read at [offset], when the guards enclosing
 * it say nothing: a cast statement or argument before the use (`foo(a as B); a.bar()`), a failed earlier `when`
 * branch (`!is A -> …` makes the next branches see an `A`), `(a as? T)!!`. Of several proven types the most
 * specific wins, and [keepDeclaredWhenNarrower] keeps a declaration that is already narrower.
 */
private fun KotlinResolver.flowNarrowing(name: String, offset: Int): KotlinType? {
    if (name == THIS_SUBJECT) return null
    val ref = elementAt(offset)?.parent as? KtNameReferenceExpression ?: return null
    if (ref.getReferencedName() != name || ref.textRange.startOffset != offset) return null
    val proven = flowProvenTypes(ref, deep = false)
    if (proven.isEmpty()) {
        // No type is proven, but a null check may still be: the declared type, made non-null, is then what the
        // value has here (`val x = if (p != null) p else "a"` is a `String`).
        val declared = localsAt(offset, name).firstOrNull()?.type as? KotlinType ?: return null
        if (!declared.nullable || declared.isTypeParameter) return null
        return if (flowProvenNonNull(ref)) declared.withNullable(false) else null
    }
    val best = proven.firstOrNull { t -> proven.all { o -> o === t || o.isAssignableFrom(t) } } ?: proven.first()
    return keepDeclaredWhenNarrower(best, name, offset)
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
        is KtBlockExpression -> earlyExitNarrowing(node, child, name, offset)?.let { return it }
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
/** `smartCastSubjectKey(expr) == name` without building a path key unless the selector already matches: this runs
 *  for every name read under every enclosing condition, so the common miss must not allocate. */
internal fun KotlinResolver.subjectMatches(expr: KtExpression?, name: String): Boolean =
    when (val e = unwrapParens(expr)) {
        is KtNameReferenceExpression -> e.getReferencedName() == name
        is KtThisExpression -> (name == THIS_SUBJECT || name.startsWith("this@")) && smartCastSubjectKey(e) == name
        is dev.ide.kotlin.syntax.psi.KtDotQualifiedExpression -> {
            val sel = (e.selectorExpression as? KtNameReferenceExpression)?.getReferencedName()
            val dot = name.lastIndexOf('.')
            sel != null && name.length - dot - 1 == sel.length && name.regionMatches(dot + 1, sel, 0, sel.length) &&
                qualifiedPathKey(e) == name
        }
        else -> false
    }

internal fun KotlinResolver.smartCastSubjectKey(expr: KtExpression?): String? =
    when (val e = unwrapParens(expr)) {
        is KtNameReferenceExpression -> e.getReferencedName()
        // `this@foo` naming the innermost receiver is that same `this` (K2 keys both to one receiver variable).
        // An outer one (`this@lambda1` inside `lambda2`) is its own receiver variable, keyed by its label: an `is`
        // check on it narrows that lambda's receiver ([computeImplicitReceiversAt]).
        is KtThisExpression -> if (e.getLabelName() == null || labeledReceiverOwner(e)?.second == true) THIS_SUBJECT
            else e.getLabelName()?.removeSurrounding("`")?.let { "this@$it" }
        is dev.ide.kotlin.syntax.psi.KtDotQualifiedExpression -> qualifiedPathKey(e)
        else -> null
    }

/** The path keys this file checks ([KotlinResolverCaches.checkedPaths]), from one walk of the file. */
internal fun KotlinResolver.checkedPaths(): Set<String> {
    caches.checkedPaths?.let { return it }
    val out = HashSet<String>()
    val aliases = HashMap<String, MutableList<String>>()
    fun add(e: KtExpression?) {
        (unwrapParens(e) as? dev.ide.kotlin.syntax.psi.KtDotQualifiedExpression)?.let { qualifiedPathKey(it) }?.let { out += it }
    }
    fun visit(p: KtElement) {
        when (p) {
            is dev.ide.kotlin.syntax.psi.KtProperty -> if (!p.isVar && p.isLocal && p.typeReference == null) {
                val key = (unwrapParens(p.initializer) as? dev.ide.kotlin.syntax.psi.KtDotQualifiedExpression)?.let { qualifiedPathKey(it) }
                val n = p.name
                if (key != null && n != null) { aliases.getOrPut(key) { ArrayList(1) } += n; out += key }
            }
            is KtIsExpression -> add(p.leftHandSide)
            is KtBinaryExpressionWithTypeRHS -> add(p.left)
            is KtWhenExpression -> add(p.subjectExpression)
            is KtBinaryExpression -> if (p.operationToken == KtTokens.EQEQ || p.operationToken == KtTokens.EXCLEQ ||
                p.operationToken == KtTokens.EQEQEQ || p.operationToken == KtTokens.EXCLEQEQEQ) { add(p.left); add(p.right) }
        }
        var c = p.firstChild
        while (c != null) { (c as? KtElement)?.let(::visit); c = c.nextSibling }
    }
    visit(ktFile)
    caches.pathAliases = aliases
    caches.checkedPaths = out
    return out
}

/** Whether the member access path [q] reads one stable value: its receiver is stable (a stable name, `this`, an
 *  object or package qualifier, a stable path) and its selector is a stable property of this project. */
private fun KotlinResolver.stablePath(q: dev.ide.kotlin.syntax.psi.KtDotQualifiedExpression, depth: Int = 0): Boolean {
    if (depth > 8) return false
    val sel = (q.selectorExpression as? KtNameReferenceExpression)?.getReferencedName() ?: return false
    val r = unwrapParens(q.receiverExpression)
    val recvType = inferType(r)
    if (recvType == null) {
        // A package qualifier: a top-level property of this project, a `val` with no getter or delegate.
        val decl = service.topLevelByName(sel).firstOrNull { it.kind == dev.ide.lang.resolve.SymbolKind.FIELD }
            ?.let { (it.declaration() as? dev.ide.lang.kotlin.parse.KotlinDomNode)?.psi as? dev.ide.kotlin.syntax.psi.KtProperty }
            ?: return false
        return !decl.isVar && decl.getter?.bodyExpression == null && !decl.hasDelegate()
    }
    // A receiver that NAMES a type (`Obj`, `example.Obj`, `AClass` for its companion) is a fixed singleton, stable
    // however it is spelled.
    val denoted = r?.let { typeDenotationFqn(it) }
    val receiverStable = denoted != null || when (r) {
        is KtThisExpression -> true
        is KtNameReferenceExpression -> smartCastInstability(r, null) == null && !varWrittenInClosure(r)
        // `example.Obj`: a package-qualified object is the same singleton as `Obj`.
        is dev.ide.kotlin.syntax.psi.KtDotQualifiedExpression -> isTypeReceiver(r) || service.isKnownType(r.text) || stablePath(r, depth + 1)
        else -> false
    }
    if (!receiverStable) return false
    // A class named as the receiver reads its COMPANION's member (`AClass.y`), which is where the property lives.
    val owner = denoted?.takeIf { d -> service.membersNamed(d, emptyList(), sel).none { it.kind == dev.ide.lang.resolve.SymbolKind.FIELD && !it.isExtension } }
        ?.let { d -> service.companionObjectFqn(d) } ?: recvType.qualifiedName
    if (owner != recvType.qualifiedName) {
        if (service.sourceClass(owner) == null || sourceMemberInstability(owner, sel) != null) return false
        val p = service.membersNamed(owner, emptyList(), sel).firstOrNull { it.kind == dev.ide.lang.resolve.SymbolKind.FIELD && !it.isExtension }
            ?.let { (it.declaration() as? dev.ide.lang.kotlin.parse.KotlinDomNode)?.psi } as? dev.ide.kotlin.syntax.psi.KtProperty ?: return false
        return !p.isVar && p.getter?.bodyExpression == null && !p.hasDelegate()
    }
    if (service.sourceClass(recvType.qualifiedName) == null) return false
    if (sourceMemberInstability(recvType.qualifiedName, sel) != null) return false
    // [sourceMemberInstability] leaves an inherited open member of a final class undecided; the declaration the
    // read reaches must itself be a plain `val` (a custom getter, a delegate or a `var` is never stable).
    val decl = service.membersNamed(recvType.qualifiedName, recvType.typeArguments, sel)
        .firstOrNull { it.kind == dev.ide.lang.resolve.SymbolKind.FIELD && !it.isExtension }
        ?.let { (it.declaration() as? dev.ide.lang.kotlin.parse.KotlinDomNode)?.psi } ?: return false
    return when (decl) {
        is dev.ide.kotlin.syntax.psi.KtProperty -> !decl.isVar && decl.getter?.bodyExpression == null && !decl.hasDelegate()
        is dev.ide.kotlin.syntax.psi.KtParameter -> !decl.isMutable
        else -> false
    }
}

/** Whether [ref] reads a local `var` that a closure in its scope (a lambda, a local function, an object) writes: the
 *  value the variable holds may then change at any time, so no member path through it is stable. */
private fun KotlinResolver.varWrittenInClosure(ref: KtNameReferenceExpression): Boolean {
    val name = ref.getReferencedName()
    val decl = (localsAt(ref.textRange.startOffset, name).firstOrNull()?.declaration() as? dev.ide.lang.kotlin.parse.KotlinDomNode)?.psi
        as? dev.ide.kotlin.syntax.psi.KtProperty ?: return false
    if (!decl.isVar) return false
    val scope = decl.parent as? KtElement ?: return false
    var found = false
    fun visit(e: KtElement, inClosure: Boolean) {
        if (found) return
        val closure = inClosure || e is dev.ide.kotlin.syntax.psi.KtLambdaExpression ||
            e is dev.ide.kotlin.syntax.psi.KtNamedFunction || e is dev.ide.kotlin.syntax.psi.KtObjectLiteralExpression
        if (closure && e is KtBinaryExpression && e.operationToken == KtTokens.EQ &&
            (unwrapParens(e.left) as? KtNameReferenceExpression)?.getReferencedName() == name
        ) { found = true; return }
        var c = e.firstChild
        while (c != null) { (c as? KtElement)?.let { visit(it, closure) }; c = c.nextSibling }
    }
    visit(scope, false)
    return found
}

/** The narrowing of the path [key] read at [offset]: a check on the path itself, else one on a local `val`
 *  initialized with it ([KotlinResolverCaches.pathAliases]). */
internal fun KotlinResolver.pathSmartCastAt(q: dev.ide.kotlin.syntax.psi.KtDotQualifiedExpression, key: String, offset: Int): KotlinType? {
    // Only a STABLE path is smart cast (K2: every step a `val` without a custom getter or open override, declared in
    // this module); an unstable one keeps its declared type, and the smart-cast-impossible check speaks for it.
    if (!stablePath(q)) return null
    smartCastTypeAt(key, offset)?.let { return it }
    for (alias in caches.pathAliases?.get(key).orEmpty()) {
        // Only while the alias is the in-scope declaration of that name.
        if (localsAt(offset, alias).isEmpty()) continue
        smartCastTypeAt(alias, offset)?.let { return it }
    }
    return null
}

/** The selectors of [checkedPaths]. */
internal fun KotlinResolver.checkedPathSelectors(): Set<String> =
    caches.checkedPathSelectors ?: checkedPaths().mapTo(HashSet()) { it.substringAfterLast('.') }
        .also { caches.checkedPathSelectors = it }

/**
 * The smart-cast key of a member access PATH (`b.x`, `Obj.y`, `example.ns.y`, `this.p`): the names joined by dots,
 * a leading `this` kept. A check on the path narrows the same path read again (K2 tracks a stable member access
 * as a variable of its own). Null for anything with a call, an index or a safe call in it.
 */
internal fun KotlinResolver.qualifiedPathKey(e: dev.ide.kotlin.syntax.psi.KtDotQualifiedExpression): String? =
    rawPathKey(e)?.let { canonicalPath(it) }

private fun KotlinResolver.rawPathKey(e: dev.ide.kotlin.syntax.psi.KtDotQualifiedExpression): String? {
    val sel = e.selectorExpression as? KtNameReferenceExpression ?: return null
    val head = when (val r = unwrapParens(e.receiverExpression)) {
        is KtNameReferenceExpression -> r.getReferencedName()
        is KtThisExpression -> if (r.getLabelName() == null) "this" else return null
        is dev.ide.kotlin.syntax.psi.KtDotQualifiedExpression -> rawPathKey(r) ?: return null
        else -> return null
    }
    return head + "." + sel.getReferencedName()
}

/** [path] as the file spells the same declaration by its shortest name: its own package dropped (`example.x` is
 *  `x` in package `example`), an explicitly imported head replaced by its simple name (`example.ns.y` is `y` under
 *  `import example.ns.y`). One variable has one key however it is written. */
private fun KotlinResolver.canonicalPath(path: String): String {
    for (imp in fileContext.imports) {
        if (imp.isStar || imp.alias != null) continue
        val f = imp.fqn
        if (path == f) return imp.simpleName
        if (path.length > f.length && path.startsWith(f) && path[f.length] == '.') return imp.simpleName + path.substring(f.length)
    }
    val pkg = fileContext.packageName
    return if (pkg.isNotEmpty() && path.length > pkg.length && path.startsWith(pkg) && path[pkg.length] == '.') path.substring(pkg.length + 1) else path
}

/**
 * The declaration a labeled `this@label` names, and whether it provides the INNERMOST implicit receiver at that
 * point: the nearest enclosing extension function, receiver lambda or class whose label is [label] (a function's
 * or class's name, a lambda's `label@` or the name of the call it is passed to). Null when no receiver owner
 * carries the label.
 */
internal fun KotlinResolver.labeledReceiverOwner(e: KtThisExpression): Pair<KtElement, Boolean>? {
    val label = e.getLabelName() ?: return null
    var first = true
    var node: KtElement? = e.parent as? KtElement
    while (node != null) {
        when (node) {
            is dev.ide.kotlin.syntax.psi.KtLambdaExpression -> if (lambdaReceiverType(node) != null) {
                if (lambdaLabel(node) == label) return node to first
                first = false
            }
            is dev.ide.kotlin.syntax.psi.KtNamedFunction -> if (node.receiverTypeReference != null) {
                if (node.name == label) return node to first
                first = false
            }
            is dev.ide.kotlin.syntax.psi.KtProperty -> if (node.receiverTypeReference != null) {
                if (node.name == label) return node to first
                first = false
            }
            is dev.ide.kotlin.syntax.psi.KtClassOrObject -> {
                if (node.name == label) return node to first
                first = false
            }
        }
        node = node.parent as? KtElement
    }
    return null
}

/** A lambda's label: its own `label@`, else the name of the call it is an argument of (`with(x) { this@with }`). */
internal fun lambdaLabel(lambda: dev.ide.kotlin.syntax.psi.KtLambdaExpression): String? {
    var e: KtElement = lambda
    while (true) {
        val p = e.parent
        if (p is dev.ide.kotlin.syntax.psi.KtLabeledExpression) return p.getLabelName()
        if (p is dev.ide.kotlin.syntax.psi.KtAnnotatedExpression || p is dev.ide.kotlin.syntax.psi.KtParenthesizedExpression) { e = p; continue }
        break
    }
    var arg: KtElement? = e.parent as? KtElement
    if (arg is dev.ide.kotlin.syntax.psi.KtValueArgument) arg = arg.parent as? KtElement
    if (arg is dev.ide.kotlin.syntax.psi.KtValueArgumentList) arg = arg.parent as? KtElement
    return ((arg as? KtCallExpression)?.calleeExpression as? KtNameReferenceExpression)?.getReferencedName()
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
            // `is T?` admits null: the value is a `T?` there, not a `T`.
            if (whenTrue != c.isNegated && subjectMatches(c.leftHandSide, name)) typeFromIsTarget(c.typeReference)
                ?.let { if (c.typeReference?.text?.trim()?.endsWith("?") == true) it.withNullable(true) else it } else null
        }

        is KtBinaryExpression -> when (c.operationToken) {
            KtTokens.ANDAND -> if (whenTrue) conditionNarrowing(c.left, name, true)
                ?: conditionNarrowing(c.right, name, true) else null

            // `x is A || x is B` taken true: `x` is one or the other, so their nearest common supertype (K2's join
            // of the two branches' type statements).
            KtTokens.OROR -> if (!whenTrue) conditionNarrowing(c.left, name, false)
                ?: conditionNarrowing(c.right, name, false)
                else joinNarrowings(conditionNarrowing(c.left, name, true), conditionNarrowing(c.right, name, true))

            KtTokens.EQEQ, KtTokens.EQEQEQ -> if (whenTrue) equalityNarrowing(c, name) else nonNullSafeCastNarrowing(c, name)
            KtTokens.EXCLEQ, KtTokens.EXCLEQEQEQ -> if (!whenTrue) equalityNarrowing(c, name) else nonNullSafeCastNarrowing(c, name)

            else -> null
        }

        // `!cond` holds exactly when `cond` does not.
        is dev.ide.kotlin.syntax.psi.KtPrefixExpression ->
            if (c.operationToken == KtTokens.EXCL) conditionNarrowing(c.baseExpression, name, !whenTrue) else null

        else -> null
    }

/** The join of two alternative narrowings: their nearest common supertype, null if either says nothing. */
private fun KotlinResolver.joinNarrowings(a: KotlinType?, b: KotlinType?): KotlinType? {
    if (a == null || b == null) return null
    if (a.qualifiedName == b.qualifiedName) return a
    return commonSupertypesType(listOf(a, b))
}

/**
 * A comparison with `null` known to fail, of a value rooted in a safe cast of the subject: `(x as? T) != null`,
 * `(x as? T)?.member != null`, `(x as? T)?.a?.b == null` taken false. The safe cast then gave a value, so `x` is a
 * `T` (K2 records `x is T` on the non-null branch of the safe cast's own null check, and a safe call chain is
 * non-null only when its receiver is).
 */
private fun KotlinResolver.nonNullSafeCastNarrowing(c: KtBinaryExpression, name: String): KotlinType? {
    fun isNull(e: KtExpression?) = unwrapParens(e).let { it is dev.ide.kotlin.syntax.psi.KtConstantExpression && it.text == "null" }
    var e: KtExpression? = when {
        isNull(c.right) -> c.left
        isNull(c.left) -> c.right
        else -> return null
    }
    while (true) {
        e = unwrapParens(e)
        e = when (e) {
            is dev.ide.kotlin.syntax.psi.KtSafeQualifiedExpression -> e.receiverExpression
            else -> break
        }
    }
    val cast = e as? dev.ide.kotlin.syntax.psi.KtBinaryExpressionWithTypeRHS ?: return null
    if (cast.operationReference.getReferencedNameElementType() != KtTokens.AS_SAFE) return null
    if (!subjectMatches(cast.left, name)) return null
    return typeFromIsTarget(cast.right)
}

/**
 * `a == b` holding: each operand then has the other's type, when the equality is known to be primitive
 * (identity-like). Port of FirDataFlowAnalyzer.processEqContractsBasedOnPrimitiveEquals: always for `===`, and
 * for `==` when the `equals` the LEFT operand's type calls is `Any`'s, `String`'s or an enum's (K2 asks its
 * EqualsOverrideContract of the left operand's type, whichever side is narrowed). Nothing when both sides are
 * nullable, or the other side is the `null` literal (a null check is the null flow's).
 */
private fun KotlinResolver.equalityNarrowing(c: KtBinaryExpression, name: String): KotlinType? {
    val other = when {
        subjectMatches(c.left, name) -> c.right
        subjectMatches(c.right, name) -> c.left
        else -> return null
    } ?: return null
    if (unwrapParens(other).let { it is dev.ide.kotlin.syntax.psi.KtConstantExpression && it.text == "null" }) return null
    val subject = if (other === c.right) c.left else c.right
    val otherType = inferType(other) ?: return null
    val subjectType = inferType(subject)
    // Equal to a value the flow already proved `null` (`if (j == null) { if (i == j) foo(i) }`): the subject is
    // `null` too, so it is `Nothing?` and fits any nullable type.
    if (otherType.nullable && subjectType?.nullable != false) {
        val otherRef = unwrapParens(other) as? KtNameReferenceExpression ?: return null
        return if (flowProvenNull(otherRef)) service.typeByFqn("kotlin.Nothing").withNullable(true) else null
    }
    // The value then has BOTH types; one type stands for that intersection only when it is the narrower of the
    // two (`current === this` on an unrelated `A` and `B` keeps `current` an `A`).
    if (subjectType != null && !subjectType.isTypeParameter && !subjectType.withNullable(false).isAssignableFrom(otherType.withNullable(false))) return null
    val identity = c.operationToken == KtTokens.EQEQEQ || c.operationToken == KtTokens.EXCLEQEQEQ
    if (!identity) {
        val leftType = (if (other === c.left) otherType else subjectType) ?: return null
        if (!equalsSafeForSmartCast(leftType, c.textRange.startOffset)) return null
    }
    return if (subjectType?.nullable == false) otherType.withNullable(false) else otherType
}

/** Whether `equals` on a value of [t] is known to be primitive: `String`'s, an enum's, or `Any`'s own in a final
 *  class (no class on the way up overrides it). A type parameter answers for its bound. */
private fun KotlinResolver.equalsSafeForSmartCast(t: KotlinType, offset: Int, depth: Int = 0): Boolean {
    if (depth > 4) return false
    if (t.isTypeParameter || isTypeParameterInScope(t.qualifiedName, offset)) {
        val bound = typeParameterUpperBound(t.qualifiedName, offset) ?: return false
        return equalsSafeForSmartCast(bound, offset, depth + 1)
    }
    val fqn = canonicalTypeForCheck(t).qualifiedName
    if (fqn == "kotlin.String") return true
    val supers = service.supertypesOf(fqn).map { it.qualifiedName }
    if ("kotlin.Enum" in supers || "java.lang.Enum" in supers) return true
    if (!service.isKnownType(fqn) || service.isFinalType(fqn) != true) return false
    // A final class whose `equals` is still `Any`'s: neither it nor a superclass declares one.
    return (listOf(fqn) + supers)
        .filter { it != "kotlin.Any" && it != "java.lang.Object" }
        .none { owner -> overridesEquals(owner) }
}

/** Whether the class [owner] itself overrides `equals(Any?)`: read off a source class's declarations, else the
 *  binary's own members. */
private fun KotlinResolver.overridesEquals(owner: String): Boolean {
    service.sourceClass(owner)?.let { rc ->
        val psi = (rc.node as? dev.ide.lang.kotlin.parse.KotlinDomNode)?.psi as? dev.ide.kotlin.syntax.psi.KtClassOrObject
            ?: return true // unknown shape: assume it might
        return psi.declarations.any {
            it is dev.ide.kotlin.syntax.psi.KtNamedFunction && it.name == "equals" && it.valueParameters.size == 1 &&
                it.hasModifier(KtTokens.OVERRIDE_KEYWORD)
        }
    }
    return service.membersNamed(owner, emptyList(), "equals").any { it.declaringClassFqn == owner && it.paramTypes.size == 1 }
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
    // K2 reaches a branch only when every EARLIER branch failed, so each earlier condition holds negated here
    // (`when { x !is T -> …; else -> ‹x is T› }`), latest first.
    val earlier = whenExpr.entries.takeWhile { it !== entry }.asReversed()
    val subjectVariable = whenExpr.subjectVariable
    if (subjectVariable != null || whenExpr.subjectExpression != null) {
        val matches = if (subjectVariable != null) subjectVariable.name == name else subjectMatches(whenExpr.subjectExpression, name)
        // `when (val x = this)`: the subject variable is the receiver itself, so a check on it narrows `this` too
        // (K2's data flow treats the `val` as an alias of the value it was initialized with).
        val aliasOfThis = !matches && name == THIS_SUBJECT &&
            subjectVariable?.initializer?.let { smartCastSubjectKey(it) } == THIS_SUBJECT
        if (!matches && !aliasOfThis) return null
        // A single positive `is T` narrows to `T`; a comma branch of positive checks (`is X, is Y`) to what the
        // types have in common, their nearest common supertypes, intersected (`A & B` for two data classes that
        // both implement `A` and `B`).
        if (!entry.isElse) {
            val patterns = entry.conditions.map { it as? KtWhenConditionIsPattern ?: return@map null }
            if (patterns.isNotEmpty() && patterns.all { it != null && !it.isNegated }) {
                if (patterns.size == 1) return typeFromIsTarget(patterns[0]!!.typeReference)
                patterns.map { typeFromIsTarget(it!!.typeReference) ?: return null }.let { commonSupertypesType(it) }?.let { return it }
            }
        }
        // A failed `!is T` branch leaves the subject a `T`.
        for (e in earlier) {
            val p = e.conditions.singleOrNull() as? KtWhenConditionIsPattern ?: continue
            if (p.isNegated) typeFromIsTarget(p.typeReference)?.let { return it }
        }
        return null
    }
    // Subjectless `when { name is T -> … }`: the branch condition is a boolean expression on names.
    if (!entry.isElse) (entry.conditions.singleOrNull() as? KtWhenConditionWithExpression)?.expression
        ?.let { c -> conditionNarrowing(c, name, whenTrue = true)?.let { return it } }
    for (e in earlier) {
        val c = (e.conditions.singleOrNull() as? KtWhenConditionWithExpression)?.expression ?: continue
        conditionNarrowing(c, name, whenTrue = false)?.let { return it }
    }
    return null
}

/** The nearest common supertypes of [types] as one type, the first heading an intersection of the rest
 *  (`X`, `Y` both `: A, B` → `A & B`); null when they share nothing but `Any`. */
internal fun KotlinResolver.commonSupertypesType(types: List<KotlinType>): KotlinType? {
    val distinct = types.distinctBy { it.qualifiedName }
    if (distinct.size == 1) return distinct[0]
    val first = distinct[0]
    // An intersection's components (and their supertypes) are candidates as much as its head's.
    val candidates = (listOf(first) + first.intersection).flatMap { h -> listOf(h.withIntersection(emptyList())) + service.supertypesOf(h.qualifiedName).filterIsInstance<KotlinType>() }
        .distinctBy { it.qualifiedName }
        .filter { c -> c.qualifiedName != "kotlin.Any" && c.qualifiedName != "java.lang.Object" && distinct.all { c.isAssignableFrom(it) } }
    // Keep only the nearest: drop one that another candidate is already a subtype of.
    val nearest = candidates.filter { c -> candidates.none { o -> o !== c && c.isAssignableFrom(o) && !o.isAssignableFrom(c) } }
    if (nearest.isEmpty()) return null
    return nearest[0].withIntersection(nearest.drop(1))
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
/**
 * The type both branches of [st] give [name] when each ends by assigning it a value of one same known, non-null
 * type, and [name] is a `var` declared earlier in [block] with no initializer (its definite assignment). Null
 * otherwise.
 */
private fun KotlinResolver.branchesAssignSameType(block: KtBlockExpression, st: KtIfExpression, name: String): KotlinType? {
    fun isWrite(s: KtExpression) = s is KtBinaryExpression && s.operationToken == KtTokens.EQ &&
        (unwrapParens(s.left) as? KtNameReferenceExpression)?.getReferencedName() == name
    fun assigned(branch: KtExpression?): KotlinType? {
        val statements = (branch as? KtBlockExpression)?.statements ?: listOfNotNull(branch)
        // The branch's one write of the variable, with nothing after it writing the name again.
        val i = statements.indexOfLast { isWrite(it) }
        if (i < 0 || statements.drop(i + 1).any { name in writtenNames(it).written }) return null
        val a = statements[i] as KtBinaryExpression
        // `x = a` with `a` a stable name: `x` is the same value as `a`, so what the branch later proves about `a`
        // (`x = a; a as A`) holds for `x` at the branch's end.
        val rhsName = (a.right?.let { unwrapParens(it) } as? KtNameReferenceExpression)?.getReferencedName()
        val atEnd = if (rhsName != null && statements.last() !== a && branch is KtBlockExpression)
            smartCastTypeAt(rhsName, branch.textRange.endOffset - 1) else null
        return (atEnd ?: a.right?.let { inferType(it) })?.takeIf { !it.isTypeParameter && !it.nullable && service.isKnownType(it.qualifiedName) }
    }
    val declared = block.statements.takeWhile { it !== st }
        .lastOrNull { it is dev.ide.kotlin.syntax.psi.KtProperty && it.name == name } as? dev.ide.kotlin.syntax.psi.KtProperty
        ?: return null
    // `var y: Any` or a deferred `val x: Any`: the branches give it its value.
    if (declared.initializer != null || declared.hasDelegate()) return null
    // A branch that never falls through (`if (x is String) break else y = "abc"`) leaves the other one's value.
    val thenJumps = branchAlwaysJumps(st.then)
    val elseJumps = branchAlwaysJumps(st.`else`)
    if (thenJumps && elseJumps) return null
    if (thenJumps) return assigned(st.`else`)
    if (elseJumps) return assigned(st.then)
    val t = assigned(st.then) ?: return null
    val e = assigned(st.`else`) ?: return null
    return t.takeIf { e.qualifiedName == t.qualifiedName }
}

/** Whether the read at [offset] inside [st] sits in a lambda that is not called in place, a local function or a
 *  local class. */
private fun KotlinResolver.readInLeakingClosure(st: KtElement, offset: Int): Boolean {
    var node: KtElement? = elementAt(offset)
    val flow = KotlinVarNullFlow(this)
    while (node != null && node !== st) {
        when (node) {
            is KtNamedFunction, is KtClassOrObject -> return true
            is KtLambdaExpression -> if (flow.inPlaceKind(node) == null) return true
        }
        node = node.parent
    }
    return false
}

/** Whether [e]'s type cannot depend on an expected type: a literal, a string, a name or `this`, a constructor or
 *  other call of a non-generic function, or a qualified such call or read. */
private fun KotlinResolver.fixedTypeValue(e: KtExpression): Boolean = when (e) {
    is dev.ide.kotlin.syntax.psi.KtConstantExpression, is dev.ide.kotlin.syntax.psi.KtStringTemplateExpression,
    is KtNameReferenceExpression, is dev.ide.kotlin.syntax.psi.KtThisExpression -> true
    is KtCallExpression -> e.typeArguments.isNotEmpty() || resolveCalleeFunction(e)?.typeParameters?.isEmpty() == true
    is dev.ide.kotlin.syntax.psi.KtDotQualifiedExpression -> when (val sel = e.selectorExpression) {
        is KtCallExpression -> fixedTypeValue(sel)
        is KtNameReferenceExpression -> true
        else -> false
    }
    else -> false
}

/** Whether [name] is assigned (`=`, a compound assignment, `++`/`--`) inside a lambda, local function or local
 *  class anywhere in [block]. */
private fun writtenInClosure(block: KtBlockExpression, name: String): Boolean {
    var found = false
    fun visit(e: KtElement, inClosure: Boolean) {
        if (found) return
        val closure = inClosure || e is KtLambdaExpression || e is KtNamedFunction || e is KtClassOrObject
        if (closure) {
            val writes = when (e) {
                is KtBinaryExpression -> e.operationToken in ASSIGNMENTS &&
                    (unwrapParenPsi(e.left) as? KtNameReferenceExpression)?.getReferencedName() == name
                is KtUnaryExpression -> (e.operationToken == KtTokens.PLUSPLUS || e.operationToken == KtTokens.MINUSMINUS) &&
                    (unwrapParenPsi(e.baseExpression) as? KtNameReferenceExpression)?.getReferencedName() == name
                else -> false
            }
            if (writes) { found = true; return }
        }
        var c = e.firstChild
        while (c != null) { visit(c, closure); c = c.nextSibling }
    }
    visit(block, false)
    return found
}

private fun unwrapParenPsi(e: KtElement?): KtElement? {
    var cur = e
    while (cur is dev.ide.kotlin.syntax.psi.KtParenthesizedExpression) cur = cur.expression
    return cur
}

/**
 * The type statement [st] leaves the variable [name] holding when [st] is a plain write of it: `name = value`,
 * or a statement whose only write of it is an increment or decrement (`++name`, `name--`, also inside an
 * initializer such as `val x = ++name`), whose new value is the operator's result. Null for anything else, or
 * when the type is unknown, nullable or a type parameter.
 */
private fun KotlinResolver.assignedValueType(st: KtExpression, name: String): KotlinType? {
    fun known(t: KotlinType?) = t?.takeIf {
        !it.isTypeParameter && !it.nullable && it.qualifiedName != "kotlin.Unit" && it.qualifiedName != "kotlin.Nothing" &&
            service.isKnownType(it.qualifiedName)
    }
    val assign = st as? KtBinaryExpression
    if (assign != null && assign.operationToken == KtTokens.EQ &&
        (unwrapParens(assign.left) as? KtNameReferenceExpression)?.getReferencedName() == name
    ) {
        // K2 types the value against the variable's own type, so a value whose type comes out of inference (a
        // generic call, an elvis, an if/when/try merge) may be the variable's type there rather than what it
        // reads as alone (`x = checkNotNull(a, b)` into an `Any` is an `Any`, KT-50875). Only a value whose type
        // no expected type can change is recorded.
        val value = unwrapParens(assign.right) ?: return null
        if (!fixedTypeValue(value)) return null
        return known(inferType(value))
    }
    if (name !in writtenNames(st).written) return null
    var found: KtUnaryExpression? = null
    var other = false
    fun visit(e: KtElement) {
        if (other || e is KtLambdaExpression || e is KtNamedFunction || e is KtClassOrObject) return
        if (e is KtUnaryExpression && (e.operationToken == KtTokens.PLUSPLUS || e.operationToken == KtTokens.MINUSMINUS) &&
            (unwrapParens(e.baseExpression) as? KtNameReferenceExpression)?.getReferencedName() == name
        ) {
            if (found != null) other = true else found = e
            return
        }
        if (e is KtBinaryExpression && e.operationToken in ASSIGNMENTS &&
            (unwrapParens(e.left) as? KtNameReferenceExpression)?.getReferencedName() == name
        ) { other = true; return }
        var c = e.firstChild
        while (c != null) { visit(c); c = c.nextSibling }
    }
    visit(st)
    val inc = found ?: return null
    if (other) return null
    val operand = inferType(inc.baseExpression)?.withNullable(false) ?: return null
    if (operand.isTypeParameter) return null
    val op = if (inc.operationToken == KtTokens.PLUSPLUS) "inc" else "dec"
    return known(if (operand.qualifiedName in NUMERIC_RANK) operand else unaryOperator(operand, op))
}

internal fun KotlinResolver.earlyExitNarrowing(
    block: KtBlockExpression,
    fromChild: KtElement?,
    name: String,
    /** Where the read is, when known: an assignment's narrowing does not reach into a closure that may run after a
     *  later write. */
    readAt: Int = -1,
): KotlinType? {
    var result: KotlinType? = null
    var fromAssignment = false
    for (st in block.statements) {
        if (st === fromChild) {
            // A narrowing an assignment made holds only up to a statement that writes the variable again on the
            // way to the read: a `catch` may follow any write in its `try`, a loop body may run after its own write.
            if (fromAssignment && name in writtenNames(st).written) return null
            // Read inside a closure that is not called in place (a stored lambda, a local function or class), with a
            // write of the variable later in the block: the closure may run after that write, so K2 does not
            // smart cast there (SMARTCAST_IMPOSSIBLE).
            if (fromAssignment && readAt >= 0 && readInLeakingClosure(st, readAt) &&
                block.statements.dropWhile { it !== st }.drop(1).any { name in writtenNames(it).written }
            ) return null
            break
        }
        // A local `var` given a value records that value's type even when nothing narrowed it before: after
        // `local = ST()`, `++local` or `local++` (whose new value is what `inc()` returns, KT-57178) the variable is
        // an `ST` until it is written again. Only a local: a property's smart cast is not stable.
        if (result == null) {
            assignedValueType(st, name)?.let { t ->
                val local = block.statements.firstOrNull { it is KtProperty && it.name == name && it.textRange.endOffset <= st.textRange.startOffset } as? KtProperty
                // A member `val` given its value in an `init` block (`private val x: I; init { x = s(); x.foo() }`):
                // a final, plain property is stable there, so the value's type holds too.
                val memberVal = local == null && block.parent is dev.ide.kotlin.syntax.psi.KtAnonymousInitializer &&
                    localsAt(st.textRange.startOffset, name).isEmpty() &&
                    (block.parent?.parent?.parent as? KtClassOrObject)?.declarations?.any {
                        it is KtProperty && it.name == name && !it.isVar && !it.hasDelegate() && it.getter == null
                    } == true
                // K2's stability rule for a captured `var` (FirLocalVariableAssignmentAnalyzer): one that a lambda,
                // local function or local class also writes is not smart cast, so only a local written nowhere but
                // in its own flow gets the assigned type.
                if ((local != null && !local.hasDelegate() || memberVal) && !writtenInClosure(block, name)) { result = t; fromAssignment = true; continue }
            }
        }
        // A write after the narrowing replaces what it said: `x = 10` makes `x` an `Int` from there on (K2 records
        // the assigned value's type), and a write nested in the statement leaves nothing known. Only looked at
        // once something narrows, so the common block pays nothing.
        if (result != null) {
            val assign = st as? KtBinaryExpression
            if (assign != null && assign.operationToken == KtTokens.EQ &&
                (unwrapParens(assign.left) as? KtNameReferenceExpression)?.getReferencedName() == name
            ) {
                result = assign.right?.let { inferType(it) }
                    ?.takeIf { !it.isTypeParameter && !it.nullable && service.isKnownType(it.qualifiedName) }
                continue
            }
            val narrowed = result
            if (name in writtenNames(st).written && !nestedWritesKeep(st, name, narrowed)) { result = null; continue }
        }
        if (st is KtBinaryExpressionWithTypeRHS) {
            if (st.operationReference.getReferencedNameElementType() == KtTokens.AS_KEYWORD &&
                subjectMatches(st.left, name)
            ) {
                typeFromIsTarget(st.right)?.let { result = it }
            }
            continue
        }
        // `require(x is T)` / `check(x is T)`: the call throws unless its condition holds, so the rest of the
        // block has it true (the stdlib's `returns() implies value` contract). A lazy message may follow.
        if (st is KtCallExpression) {
            val callee = (st.calleeExpression as? KtNameReferenceExpression)?.getReferencedName()
            if (callee == "require" || callee == "check") {
                st.valueArguments.takeIf { it.size <= 2 }?.firstOrNull()?.getArgumentExpression()
                    ?.let { conditionNarrowing(it, name, whenTrue = true) }?.let { result = it }
            }
            continue
        }
        if (st is KtWhenExpression) { mergedBranchNarrowing(st, name)?.let { result = it }; continue }
        val guard = st as? KtIfExpression ?: continue
        if (guard.`else` != null) {
            // `var y: Any` given its first value in both branches (`if (c) y = "a" else y = "b"`): K2 merges the
            // two assignments' types, so after the `if` the variable has their common type.
            (branchesAssignSameType(block, guard, name) ?: mergedBranchNarrowing(guard, name))?.let { result = it }
            continue                                       // a fall-through `else` isn't an early exit
        }
        if (!branchAlwaysJumps(guard.then)) continue       // the then must transfer control out
        // After `if (cond) <jump>`, the rest of the block holds cond == false.
        conditionNarrowing(guard.condition, name, whenTrue = false)?.let { result = it }
    }
    return result
}

/**
 * K2's join of the flow after a branching statement [st]: every branch that falls through leaves [name] narrowed,
 * and after the statement it is the nearest common supertype of those narrowings (`when (x) { is A -> …; is B -> …;
 * else -> throw … }` leaves `x` the supertype `A` and `B` share). Null when some branch falls through without
 * narrowing, when no branch falls through, when a branch writes [name], or when the join is only `Any`.
 */
private fun KotlinResolver.mergedBranchNarrowing(st: KtExpression, name: String): KotlinType? {
    if (name in writtenNames(st).written) return null
    val types = ArrayList<KotlinType>()
    fun branch(body: KtExpression?, narrowing: () -> KotlinType?): Boolean {
        if (branchAlwaysJumps(body)) return true
        types += narrowing() ?: return false
        return true
    }
    fun collect(e: KtExpression): Boolean = when (e) {
        is KtIfExpression -> {
            val els = e.`else`
            val elseIf = (els as? KtBlockExpression)?.statements?.singleOrNull() as? KtIfExpression ?: els as? KtIfExpression
            els != null && branch(e.then) { conditionNarrowing(e.condition, name, whenTrue = true) } &&
                (if (elseIf != null) collect(elseIf) else branch(els) { conditionNarrowing(e.condition, name, whenTrue = false) })
        }
        // Without an `else` a non-exhaustive `when` falls through having narrowed nothing.
        is KtWhenExpression -> e.entries.any { it.isElse } && e.entries.all { en -> branch(en.expression) { whenSubjectNarrowing(e, en, name) } }
        else -> false
    }
    if (!collect(st) || types.isEmpty()) return null
    val distinct = types.distinctBy { it.qualifiedName }
    if (distinct.size == 1) return distinct[0]
    val first = distinct[0]
    val chain = listOf(first) + service.supertypesOf(first.qualifiedName).filterIsInstance<KotlinType>()
    return chain.firstOrNull { c -> distinct.all { c.isAssignableFrom(it) } }?.takeIf { it.qualifiedName != "kotlin.Any" }
}

/**
 * Whether the narrowing to [narrowed] survives the writes of [name] nested in statement [e]: each must be a plain
 * `=` of a value that is itself a [narrowed] (`if (n != null) c = n` keeps `c` a `String`, as K2's merge of the
 * branches does). A compound assignment, `++`/`--`, or a value of another or unknown type leaves nothing known.
 * Writes inside a lambda, local function or class are a question of the value's stability rather than of what it
 * holds here, and a block that declares its own [name] hides the outer one from that declaration on.
 */
private fun KotlinResolver.nestedWritesKeep(e: KtElement, name: String, narrowed: KotlinType): Boolean {
    when (e) {
        is dev.ide.kotlin.syntax.psi.KtLambdaExpression, is dev.ide.kotlin.syntax.psi.KtNamedFunction,
        is dev.ide.kotlin.syntax.psi.KtClassOrObject -> return true
        is KtBlockExpression -> {
            for (st in e.statements) {
                if (st is dev.ide.kotlin.syntax.psi.KtProperty && st.name == name) return st.initializer?.let { nestedWritesKeep(it, name, narrowed) } ?: true
                if (!nestedWritesKeep(st, name, narrowed)) return false
            }
            return true
        }
        is KtBinaryExpression -> if (e.operationToken == KtTokens.EQ || e.operationToken in WRITE_OPS) {
            if ((unwrapParens(e.left) as? KtNameReferenceExpression)?.getReferencedName() == name) {
                if (e.operationToken != KtTokens.EQ) return false
                val t = e.right?.let { inferType(it) } ?: return false
                if (t.isTypeParameter || t.nullable || !service.isKnownType(t.qualifiedName)) return false
                if (!narrowed.isAssignableFrom(t)) return false
                return e.right?.let { nestedWritesKeep(it, name, narrowed) } ?: true
            }
        }
        is dev.ide.kotlin.syntax.psi.KtUnaryExpression -> if (e.operationToken == KtTokens.PLUSPLUS || e.operationToken == KtTokens.MINUSMINUS) {
            if ((unwrapParens(e.baseExpression) as? KtNameReferenceExpression)?.getReferencedName() == name) return false
        }
    }
    var c = e.firstChild
    while (c != null) {
        if (c is KtElement && !nestedWritesKeep(c, name, narrowed)) return false
        c = c.nextSibling
    }
    return true
}

/** Whether [branch] unconditionally transfers control out of the enclosing block (so code after the guard
 *  is reached only when the guard's condition was false): a `return`/`throw`/`break`/`continue`, or a block
 *  whose last statement does. */
internal fun KotlinResolver.branchAlwaysJumps(branch: KtExpression?): Boolean =
    when (val b = branch) {
        is KtReturnExpression, is KtThrowExpression, is KtBreakExpression, is KtContinueExpression -> true
        is KtBlockExpression -> branchAlwaysJumps(b.statements.lastOrNull())
        // A call typed `Nothing` never returns either, and `?: error("…")` / `?: TODO()` is how half of
        // Kotlin spells an early exit. Checked LAST, so the syntactic cases above never pay for an inference.
        // A `Nothing?` one returns, with `null`.
        is KtCallExpression -> inferType(b)?.let { it.qualifiedName == "kotlin.Nothing" && !it.nullable } == true
        else -> false
    }

/** The classifier a smart-cast `is T` narrows to: generic args erased and made non-null. Null when [ref] is
 *  absent or doesn't resolve. Takes the type REFERENCE, not its text, so the enclosing class is available and
 *  a nested `is T` target resolves by simple name. */
internal fun KotlinResolver.typeFromIsTarget(ref: KtTypeReference?): KotlinType? {
    // Every segment's arguments go, not just the text after the first `<` (`Outer<*>.Inner<*>` is `Outer.Inner`).
    val text = ref?.text?.let(::eraseTypeArguments)?.removeSuffix("?")?.trim()?.takeIf { it.isNotEmpty() }
        ?: return null
    return runCatching { service.typeFromText(text, fileContext, enclosingClassFqnOf(ref)) }.getOrNull()
}

/** [text] with every `<…>` group removed (`Outer<*>.Inner<Int>` → `Outer.Inner`); a `->` inside is not a close. */
internal fun eraseTypeArguments(text: String): String {
    if ('<' !in text) return text
    val out = StringBuilder(text.length)
    var depth = 0
    for ((i, ch) in text.withIndex()) {
        when {
            ch == '<' -> depth++
            ch == '>' && text.getOrNull(i - 1) != '-' && depth > 0 -> depth--
            depth == 0 -> out.append(ch)
        }
    }
    return out.toString()
}
