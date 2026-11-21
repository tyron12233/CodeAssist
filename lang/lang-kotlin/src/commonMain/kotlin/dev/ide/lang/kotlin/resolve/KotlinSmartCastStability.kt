package dev.ide.lang.kotlin.resolve

import dev.ide.kotlin.syntax.psi.KtBinaryExpression
import dev.ide.kotlin.syntax.psi.KtBlockExpression
import dev.ide.kotlin.syntax.psi.KtCallExpression
import dev.ide.kotlin.syntax.psi.KtCatchClause
import dev.ide.kotlin.syntax.psi.KtClass
import dev.ide.kotlin.syntax.psi.KtClassOrObject
import dev.ide.kotlin.syntax.psi.KtConstantExpression
import dev.ide.kotlin.syntax.psi.KtDestructuringDeclaration
import dev.ide.kotlin.syntax.psi.KtDotQualifiedExpression
import dev.ide.kotlin.syntax.psi.KtElement
import dev.ide.kotlin.syntax.psi.KtExpression
import dev.ide.kotlin.syntax.psi.KtFile
import dev.ide.kotlin.syntax.psi.KtForExpression
import dev.ide.kotlin.syntax.psi.KtFunction
import dev.ide.kotlin.syntax.psi.KtFunctionLiteral
import dev.ide.kotlin.syntax.psi.KtIfExpression
import dev.ide.kotlin.syntax.psi.KtLabeledExpression
import dev.ide.kotlin.syntax.psi.KtAnnotatedExpression
import dev.ide.kotlin.syntax.psi.KtLambdaExpression
import dev.ide.kotlin.syntax.psi.KtLoopExpression
import dev.ide.kotlin.syntax.psi.KtModifierListOwner
import dev.ide.kotlin.syntax.psi.KtNameReferenceExpression
import dev.ide.kotlin.syntax.psi.KtNamedFunction
import dev.ide.kotlin.syntax.psi.KtParenthesizedExpression
import dev.ide.kotlin.syntax.psi.KtProperty
import dev.ide.kotlin.syntax.psi.KtPropertyAccessor
import dev.ide.kotlin.syntax.psi.KtSecondaryConstructor
import dev.ide.kotlin.syntax.psi.KtClassInitializer
import dev.ide.kotlin.syntax.psi.KtDeclarationWithBody
import dev.ide.kotlin.syntax.psi.KtStringTemplateExpression
import dev.ide.kotlin.syntax.psi.KtThisExpression
import dev.ide.kotlin.syntax.psi.KtTokens
import dev.ide.kotlin.syntax.psi.KtUnaryExpression
import dev.ide.kotlin.syntax.psi.KtWhenExpression
import dev.ide.lang.kotlin.symbols.KotlinType
import dev.ide.lang.resolve.SymbolKind

/**
 * Smart-cast STABILITY the way K2 judges it (`SmartcastStability`), for the editor's SMARTCAST_IMPOSSIBLE check.
 *
 * The editor's own narrowing ([smartCastTypeAt], [nullGuardedNonNullAt]) is position-based and deliberately does
 * not ask whether the value could change between the check and the use: a narrowing it applies to an unstable
 * value only keeps a member call from being reported as unresolved. This file answers the other half, why K2
 * refuses that very smart cast, so the check can report it. Every answer here is "unstable, for this reason" or
 * null, and null covers both "stable" and "the source does not show": a library member, an unresolved name, a
 * receiver lambda that may own the name. Reporting only on a definite reason keeps the check free of false
 * positives at the price of the cases it cannot see.
 */
internal enum class SmartCastInstability(val description: String) {
    PROPERTY_WITH_GETTER("property that has an open or custom getter"),
    CAPTURED_VARIABLE("local variable that is mutated in a capturing closure"),
    MUTABLE_PROPERTY("mutable property that could be mutated concurrently"),
    DELEGATED_PROPERTY("delegated property"),
}

/**
 * Why K2 cannot smart cast the value the bare name (or `this.name`) [ref] reads, or null. [narrowedTo] is the
 * type an `is` check narrowed it to, or null for a null check; it decides which later assignments keep a
 * captured `var`'s smart cast intact (K2 forgives an assignment of a value that already has the narrowed type).
 */
internal fun KotlinResolver.smartCastInstability(ref: KtNameReferenceExpression, narrowedTo: KotlinType?): SmartCastInstability? {
    val name = ref.getReferencedName()
    val offset = ref.textRange.startOffset
    val parent = ref.parent
    val qualified = parent is KtDotQualifiedExpression && parent.selectorExpression === ref
    // `this.name` reads the member like the bare name does; any other receiver is not judged here.
    if (qualified && unwrapParens((parent as KtDotQualifiedExpression).receiverExpression)
            .let { it !is KtThisExpression || it.getLabelName() != null }) return null
    var child: KtElement = ref
    var node: KtElement? = ref.parent
    // Whether the walk has left a function or accessor body: a plain constructor parameter is in scope only in
    // the class's initializers.
    var inMember = false
    while (node != null) {
        if (node is KtNamedFunction || node is KtPropertyAccessor || node is KtSecondaryConstructor) inMember = true
        when (node) {
            is KtBlockExpression -> if (!qualified) {
                val decl = node.statements.takeWhile { it !== child && it.textRange.endOffset <= offset }.lastOrNull {
                    (it is KtProperty && it.name == name) || (it is KtDestructuringDeclaration && it.entries.any { e -> e.name == name })
                }
                if (decl is KtDestructuringDeclaration) return null
                if (decl is KtProperty) return localInstability(decl, ref, narrowedTo)
            }
            is KtFunctionLiteral -> if (!qualified) {
                if (node.valueParameters.any { it.name == name || it.destructuringDeclaration?.entries?.any { e -> e.name == name } == true }) return null
                // The implicit parameter of a one-parameter lambda.
                if (name == "it" && node.valueParameters.isEmpty()) return null
            }
            is KtFunction -> {
                if (!qualified && node.valueParameters.any { it.name == name && !it.hasValOrVar() }) return null
                // An extension function's receiver: its members are read by bare name.
                if (node is KtNamedFunction && node.receiverTypeReference != null) return receiverMemberInstability(name, offset)
            }
            is KtForExpression -> if (!qualified && child === node.body) {
                val lp = node.loopParameter
                if (lp?.name == name || lp?.destructuringDeclaration?.entries?.any { it.name == name } == true) return null
            }
            is KtCatchClause -> if (!qualified && node.catchParameter?.name == name) return null
            is KtWhenExpression -> if (!qualified && node.subjectVariable?.name == name && child !== node.subjectVariable) return null
            is KtClassOrObject -> {
                if (!inMember && node.primaryConstructor?.valueParameters?.any { it.name == name && !it.hasValOrVar() } == true) return null
                return receiverMemberInstability(name, offset)
            }
            is KtFile -> {
                if (qualified) return null
                val top = node.declarations.firstOrNull { it is KtProperty && it.name == name } as? KtProperty ?: return null
                if (top.receiverTypeReference != null || top.hasModifier(KtTokens.EXPECT_KEYWORD)) return null
                // Nothing in scope may hide it: no member of an implicit receiver.
                if (implicitReceiversAt(offset).any { memberNamed(it, name) != null }) return null
                return propertyInstability(top, overridable = false)
            }
        }
        child = node
        node = node.parent
    }
    return null
}

/**
 * The reason the member [name] read by bare name is unstable: it is the member of the innermost implicit receiver
 * (an enclosing class, an extension receiver, a receiver lambda's) that has a property of that name.
 */
private fun KotlinResolver.receiverMemberInstability(name: String, offset: Int): SmartCastInstability? {
    if (localsAt(offset, name).isNotEmpty()) return null
    val recv = implicitReceiversAt(offset).firstOrNull { r -> memberNamed(r, name) != null } ?: return null
    if (memberNamed(recv, name)?.kind != SymbolKind.FIELD) return null
    return sourceMemberInstability(recv.qualifiedName, name)
}

/**
 * Why the property [name] of a source class [ownerFqn] (declared there or in a source superclass) is unstable,
 * judged at its most derived declaration. Null when stable, or when the class or the declaring supertype is
 * not source: a library property is unstable for K2 too (declared in another module), but which members a
 * library class really has is not something this model knows exactly enough to report on.
 */
internal fun KotlinResolver.sourceMemberInstability(ownerFqn: String, name: String): SmartCastInstability? {
    val seen = HashSet<String>()
    val queue = ArrayDeque(listOf(ownerFqn))
    fun psiOf(fqn: String) = service.sourceClass(fqn)?.let { (it.node as? dev.ide.lang.kotlin.parse.KotlinDomNode)?.psi as? KtClassOrObject }
    fun isFinal(cls: KtClassOrObject) = (cls as? KtClass)?.isInterface() != true && !cls.hasModifier(KtTokens.OPEN_KEYWORD) &&
        !cls.hasModifier(KtTokens.ABSTRACT_KEYWORD) && !cls.hasModifier(KtTokens.SEALED_KEYWORD)
    val owner = psiOf(ownerFqn) ?: return null
    val ownerFinal = isFinal(owner)
    while (queue.isNotEmpty()) {
        val fqn = queue.removeFirst()
        if (!seen.add(fqn) || seen.size > 16) continue
        val cls = psiOf(fqn) ?: return null
        val interfaceMember = (cls as? KtClass)?.isInterface() == true
        // A member of a final class cannot be overridden whatever it says; a final class's inherited open member
        // is left alone (K2 judges the fake override by modality, which this does not model).
        fun overridable(d: KtModifierListOwner): Boolean? {
            val open = interfaceMember || d.hasModifier(KtTokens.OPEN_KEYWORD) || d.hasModifier(KtTokens.ABSTRACT_KEYWORD) ||
                (d.hasModifier(KtTokens.OVERRIDE_KEYWORD) && !d.hasModifier(KtTokens.FINAL_KEYWORD))
            if (!open) return false
            if (cls === owner) return if (isFinal(cls)) false else true
            return if (ownerFinal) null else true
        }
        val prop = cls.declarations.firstOrNull { it is KtProperty && it.name == name } as? KtProperty
        if (prop != null) {
            if (prop.hasModifier(KtTokens.EXPECT_KEYWORD)) return null
            return propertyInstability(prop, overridable(prop) ?: return null)
        }
        val param = cls.primaryConstructor?.valueParameters?.firstOrNull { it.name == name && it.hasValOrVar() }
        if (param != null) {
            if (param.isMutable) return SmartCastInstability.MUTABLE_PROPERTY
            return if (overridable(param) ?: return null) SmartCastInstability.PROPERTY_WITH_GETTER else null
        }
        service.supertypesOf(fqn).forEach { queue.addLast(it.qualifiedName) }
    }
    return null
}

/** A source property's own reason: a delegate, `var`, a getter with a body, or a modality that allows overriding. */
private fun propertyInstability(p: KtProperty, overridable: Boolean): SmartCastInstability? = when {
    p.hasDelegate() -> SmartCastInstability.DELEGATED_PROPERTY
    p.isVar -> SmartCastInstability.MUTABLE_PROPERTY
    p.getter?.bodyExpression != null || overridable -> SmartCastInstability.PROPERTY_WITH_GETTER
    else -> null
}

/** A local declaration's reason: a delegate (`val x by …`), or a `var` some closure may change under the use. */
private fun KotlinResolver.localInstability(local: KtProperty, ref: KtNameReferenceExpression, narrowedTo: KotlinType?): SmartCastInstability? {
    if (local.hasDelegate()) return SmartCastInstability.DELEGATED_PROPERTY
    if (!local.isVar) return null
    return if (capturedVarUnstable(local, ref, narrowedTo)) SmartCastInstability.CAPTURED_VARIABLE else null
}

/**
 * K2's captured-variable rule (`FirLocalVariableAssignmentAnalyzer`) for the local `var` [v] read at [ref]. A
 * `var` is stable unless some code that may run while a closure exists assigns it a value that breaks the smart
 * cast. Concretely, it is unstable at the read when an assignment that breaks it is
 *  - inside a closure that is not called in place (a lambda, a local function, a local class) around the read,
 *    or anywhere that can run after that closure is created (later in the code, or in a loop around it);
 *  - inside a closure that is not called in place, created before the read (its writes may happen at any time
 *    from then on);
 *  - inside another lambda argument of a call whose lambda the read is in (K2 resolves a call's lambdas
 *    together, so one's writes are unknown inside the others, the enclosing ones included);
 *  - inside a lambda passed to a call that is itself an earlier argument of a call around the read (any write
 *    there counts: that call is not completed when the later argument is resolved).
 * An assignment BREAKS the smart cast when it is not resolved yet when the read is (it comes later in the code),
 * or when it stores a literal that does not have the narrowed type (`null` for a null check). An earlier write of
 * any other value is taken to keep it, which may miss an error but never invents one.
 */
private fun KotlinResolver.capturedVarUnstable(v: KtProperty, ref: KtNameReferenceExpression, narrowedTo: KotlinType?): Boolean {
    val scope = v.parent as? KtBlockExpression ?: return false
    val name = v.name ?: return false
    val writes = assignmentsTo(v, scope)
    if (writes.isEmpty()) return false
    val at = ref.textRange.startOffset
    val flow = KotlinVarNullFlow(this)
    fun contains(outer: KtElement, inner: KtElement) =
        outer.textRange.startOffset <= inner.textRange.startOffset && inner.textRange.endOffset <= outer.textRange.endOffset
    fun breaks(w: Write) = w.at > at || w.storesLiteralBreaking(narrowedTo)
    fun notInPlace(c: KtElement): Boolean = when (c) {
        is KtLambdaExpression -> flow.inPlaceKind(c) == null
        is KtNamedFunction -> true
        is KtClassOrObject -> (c as? dev.ide.kotlin.syntax.psi.KtObjectDeclaration)?.isObjectLiteral() != true
        else -> false
    }
    // The closures between the read and the variable's block, innermost first.
    val around = ArrayList<KtElement>()
    run {
        var n: KtElement? = ref.parent
        while (n != null && n !== scope) {
            if (n is KtLambdaExpression || n is KtNamedFunction || n is KtClassOrObject) around += n
            n = n.parent
        }
    }
    for (c in around) {
        // An object literal runs in place, but what any of its members writes is unknown in all of them.
        if (c is KtClassOrObject && !notInPlace(c)) {
            if (writes.any { contains(c, it.expr) && breaks(it) }) return true
            continue
        }
        if (!notInPlace(c)) continue
        val loopStart = loopAround(c, v)
        for (w in writes) {
            if (!breaks(w)) continue
            if (contains(c, w.expr)) return true
            if (w.at >= c.textRange.endOffset || (loopStart != null && w.at >= loopStart)) return true
        }
    }
    for (w in writes) {
        if (!breaks(w) || contains(ref, w.expr)) continue
        // A closure created before the read, which does not hold it.
        var n: KtElement? = w.expr.parent
        while (n != null && n !== scope) {
            if ((n is KtLambdaExpression || n is KtNamedFunction || n is KtClassOrObject) && notInPlace(n) &&
                n.textRange.startOffset < at && !contains(n, ref)) return true
            n = n.parent
        }
    }
    // The lambdas of the calls the read is resolved within.
    val innermost = around.firstOrNull { it is KtLambdaExpression } as? KtLambdaExpression
    var n: KtElement? = ref.parent
    var lambdaSeen = false
    while (n != null && n !== scope) {
        if (n is KtLambdaExpression) lambdaSeen = true
        val call = n as? KtCallExpression
        if (call != null && !contains(call.calleeExpression ?: call, ref)) {
            for (arg in call.valueArguments) {
                val x = arg.getArgumentExpression() ?: continue
                val lam = lambdaOf(x)
                if (lam != null && innermost != null && lambdaSeen && lam !== innermost &&
                    writes.any { contains(lam, it.expr) && breaks(it) }) return true
                // An earlier argument holding a call with a lambda: that call is completed with this one.
                if (lam == null && x.textRange.endOffset <= at && writesInNestedCallLambda(x, writes)) return true
            }
        }
        n = n.parent
    }
    return false
}

private class Write(val expr: KtExpression, val value: KtExpression?) {
    val at: Int get() = expr.textRange.startOffset

    /** Whether this stores a literal whose type is not [narrowedTo] (`null` for a null check, which wants non-null). */
    fun storesLiteralBreaking(narrowedTo: KotlinType?): Boolean {
        val e = value ?: return false
        if (e is KtConstantExpression && e.text == "null") return true
        if (narrowedTo == null) return false
        val literal = when {
            e is KtStringTemplateExpression -> "kotlin.String"
            e is KtConstantExpression && (e.text == "true" || e.text == "false") -> "kotlin.Boolean"
            e is KtConstantExpression && e.text.startsWith("'") -> "kotlin.Char"
            e is KtConstantExpression && e.text.isNotEmpty() && e.text.all { it.isDigit() || it == '_' } -> "kotlin.Int"
            else -> return false
        }
        val t = narrowedTo.qualifiedName
        if (t == literal || t == "kotlin.Any") return false
        // Only a plain final classifier is compared: `Int` against `String` breaks, `Comparable`/`Number` might not.
        return t in setOf("kotlin.String", "kotlin.Int", "kotlin.Boolean", "kotlin.Char", "kotlin.Long", "kotlin.Double")
    }
}

/** Every assignment, compound assignment and `++`/`--` of [v] in [scope], by reads that bind to [v]. */
private fun assignmentsTo(v: KtProperty, scope: KtBlockExpression): List<Write> {
    val name = v.name ?: return emptyList()
    val out = ArrayList<Write>()
    fun target(e: KtExpression?): Boolean {
        var x = e
        while (x is KtParenthesizedExpression) x = x.expression
        val r = x as? KtNameReferenceExpression ?: return false
        return r.getReferencedName() == name && bindsTo(r, v, scope)
    }
    fun rec(p: KtElement) {
        if (p.textRange.endOffset <= v.textRange.endOffset && p !== scope && !contains(p, v)) return
        when (p) {
            is KtBinaryExpression -> if ((p.operationToken == KtTokens.EQ || p.operationToken in COMPOUND) && target(p.left)) {
                out += Write(p, if (p.operationToken == KtTokens.EQ) unwrapValue(p.right) else null)
            }
            is KtUnaryExpression -> if ((p.operationToken == KtTokens.PLUSPLUS || p.operationToken == KtTokens.MINUSMINUS) && target(p.baseExpression)) {
                out += Write(p, null)
            }
        }
        var c = p.firstChild
        while (c != null) { rec(c); c = c.nextSibling }
    }
    rec(scope)
    return out
}

private fun contains(outer: KtElement, inner: KtElement) =
    outer.textRange.startOffset <= inner.textRange.startOffset && inner.textRange.endOffset <= outer.textRange.endOffset

private fun unwrapValue(e: KtExpression?): KtExpression? {
    var x = e
    while (x is KtParenthesizedExpression) x = x.expression
    return x
}

/** Whether the name [r] reads is the local [v]: no declaration of the name between it and [v]'s block hides it. */
private fun bindsTo(r: KtNameReferenceExpression, v: KtProperty, scope: KtBlockExpression): Boolean {
    val name = v.name ?: return false
    val offset = r.textRange.startOffset
    if (offset < v.textRange.endOffset) return false
    var child: KtElement = r
    var node: KtElement? = r.parent
    while (node != null && node !== scope) {
        when (node) {
            is KtBlockExpression -> for (st in node.statements) {
                if (st === child || st.textRange.startOffset > offset) break
                if (st is KtProperty && st.name == name) return false
                if (st is KtDestructuringDeclaration && st.entries.any { it.name == name }) return false
            }
            is KtFunction -> if (node.valueParameters.any { it.name == name }) return false
            is KtForExpression -> if (child === node.body && (node.loopParameter?.name == name ||
                    node.loopParameter?.destructuringDeclaration?.entries?.any { it.name == name } == true)) return false
            is KtCatchClause -> if (node.catchParameter?.name == name) return false
            is KtClassOrObject -> if (node.declarations.any { it is KtProperty && it.name == name } ||
                node.primaryConstructor?.valueParameters?.any { it.name == name } == true) return false
        }
        child = node
        node = node.parent
    }
    if (node !== scope) return false
    // In [v]'s own block, a later redeclaration before the read would hide it.
    return scope.statements.none { it is KtProperty && it !== v && it.name == name &&
        it.textRange.startOffset > v.textRange.startOffset && it.textRange.endOffset <= offset }
}

/** The start of the outermost loop around [closure] that does not also hold [v]'s declaration, or null. */
private fun loopAround(closure: KtElement, v: KtProperty): Int? {
    var start: Int? = null
    var n: KtElement? = closure.parent
    while (n != null) {
        if (contains(n, v)) break
        if (n is KtLoopExpression) start = n.textRange.startOffset
        n = n.parent
    }
    return start
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

/** Whether [arg] (an argument expression) holds a call, not inside a lambda body of its own, one of whose lambda
 *  arguments contains one of [writes]. */
private fun writesInNestedCallLambda(arg: KtExpression, writes: List<Write>): Boolean {
    var found = false
    fun rec(p: KtElement) {
        if (found) return
        if (p is KtFunctionLiteral) return
        if (p is KtCallExpression) {
            for (a in p.valueArguments) {
                val lam = lambdaOf(a.getArgumentExpression()) ?: continue
                if (writes.any { contains(lam, it.expr) }) { found = true; return }
            }
        }
        var c = p.firstChild
        while (c != null) { rec(c); c = c.nextSibling }
    }
    rec(arg)
    return found
}

private val COMPOUND = setOf(KtTokens.PLUSEQ, KtTokens.MINUSEQ, KtTokens.MULTEQ, KtTokens.DIVEQ, KtTokens.PERCEQ)

/**
 * The `is` narrowing [smartCastTypeAt] applies to [name] at [offset], with where it starts: the start of the
 * construct that narrows (the `if`, the `when`, the block holding a `require`/early exit). Null when nothing
 * there narrows it.
 */
internal fun KotlinResolver.isNarrowingWithSite(name: String, offset: Int): Pair<KotlinType, Int>? {
    var child: KtElement? = null
    var node: KtElement? = elementAt(offset)
    while (node != null) {
        narrowingAtNode(node, child, offset, name)?.let { return it to node.textRange.startOffset }
        child = node
        node = node.parent
    }
    return null
}

/**
 * Whether something between [from] and the read [ref] writes the name it reads (or a member through it) in a way
 * that replaces what the narrowing said: any write before the read in the code, except one inside a closure that
 * is not called in place and does not hold the read (those are what make a value unstable, not what reassigns it
 * on the way). An assignment changes what K2 knows of the value, so the check stands back.
 */
internal fun KotlinResolver.reassignedBefore(ref: KtNameReferenceExpression, from: Int): Boolean {
    val name = ref.getReferencedName()
    val at = ref.textRange.startOffset
    val flow = KotlinVarNullFlow(this)
    val root: KtElement = ref.containingKtFile
    fun writeTarget(e: KtExpression?): Boolean {
        var x = e
        while (true) {
            x = when (x) {
                is KtParenthesizedExpression -> x.expression
                is KtDotQualifiedExpression -> if (unwrapParens(x.receiverExpression) is KtThisExpression) x.selectorExpression else x.receiverExpression
                is dev.ide.kotlin.syntax.psi.KtArrayAccessExpression -> x.arrayExpression
                else -> break
            }
        }
        return (x as? KtNameReferenceExpression)?.getReferencedName() == name
    }
    fun closureHidesWrite(w: KtElement): Boolean {
        var n: KtElement? = w.parent
        while (n != null && n !== root) {
            val closure = when (n) {
                is KtLambdaExpression -> flow.inPlaceKind(n) == null
                is KtNamedFunction -> true
                is KtClassOrObject -> true
                else -> false
            }
            if (closure && !contains(n, ref)) return true
            n = n.parent
        }
        return false
    }
    var found = false
    fun rec(p: KtElement) {
        if (found) return
        val r = p.textRange
        if (r.endOffset <= from || r.startOffset >= at) return
        val target = when (p) {
            is KtBinaryExpression -> if (p.operationToken == KtTokens.EQ || p.operationToken in COMPOUND) p.left else null
            is KtUnaryExpression -> if (p.operationToken == KtTokens.PLUSPLUS || p.operationToken == KtTokens.MINUSMINUS) p.baseExpression else null
            else -> null
        }
        if (target != null && r.startOffset >= from && writeTarget(target) && !closureHidesWrite(p)) { found = true; return }
        var c = p.firstChild
        while (c != null) { rec(c); c = c.nextSibling }
    }
    rec(root)
    return found
}

/** The declared type of what the bare name [ref] reads, before any smart cast: a local's or parameter's type, a
 *  member's or top-level property's declared type. Null when it cannot be told. */
internal fun KotlinResolver.declaredTypeOfName(ref: KtNameReferenceExpression): KotlinType? {
    val name = ref.getReferencedName()
    val offset = ref.textRange.startOffset
    localsAt(offset, name).firstOrNull()?.let { return it.type as? KotlinType }
    for (recv in implicitReceiversAt(offset)) {
        val m = memberNamed(recv, name) ?: continue
        if (m.kind != SymbolKind.FIELD) return null
        val live = sameFileTypeMember(recv.qualifiedName, name)?.takeIf { it.kind == SymbolKind.FIELD }
        return (live?.type ?: m.type) as? KotlinType
    }
    val top = ktFile.declarations.firstOrNull { it is KtProperty && it.receiverTypeReference == null && it.name == name } as? KtProperty
        ?: return null
    return sameFileProperty(top, null).type as? KotlinType
}

/**
 * Where a null check that makes [name] non-null at [offset] is written, read syntactically from the constructs
 * around the use: the then-branch of `if (name != null)` (also as a conjunct of `&&`), the else-branch of
 * `if (name == null)`, the right side of `name != null && …` / `name == null || …`, and the statements after an
 * `if (name == null) return`. Unlike [nullGuardedNonNullAt] it does not give up when the name is written in a
 * closure along the way, since that write is exactly what makes the smart cast impossible; a write outside
 * closures is [reassignedBefore]'s business.
 */
internal fun KotlinResolver.nullCheckSiteAt(name: String, offset: Int): Int? {
    fun checks(cond: KtExpression?, whenTrue: Boolean): Boolean {
        val c = unwrapParens(cond) as? KtBinaryExpression ?: return false
        when (c.operationToken) {
            KtTokens.ANDAND -> return whenTrue && (checks(c.left, true) || checks(c.right, true))
            KtTokens.OROR -> return !whenTrue && (checks(c.left, false) || checks(c.right, false))
            KtTokens.EXCLEQ, KtTokens.EQEQ -> {
                val l = unwrapParens(c.left)
                val r = unwrapParens(c.right)
                val isNull = { e: KtExpression? -> e is KtConstantExpression && e.text == "null" }
                val isName = { e: KtExpression? -> (e as? KtNameReferenceExpression)?.getReferencedName() == name }
                if (!((isName(l) && isNull(r)) || (isNull(l) && isName(r)))) return false
                return (c.operationToken == KtTokens.EXCLEQ) == whenTrue
            }
            else -> return false
        }
    }
    var child: KtElement? = null
    var node: KtElement? = elementAt(offset)
    while (node != null) {
        when (node) {
            is KtIfExpression -> {
                if (node.then?.textRange?.contains(offset) == true && checks(node.condition, true)) return node.textRange.startOffset
                if (node.`else`?.textRange?.contains(offset) == true && checks(node.condition, false)) return node.textRange.startOffset
            }
            is KtBinaryExpression -> if (node.right?.textRange?.contains(offset) == true) {
                if (node.operationToken == KtTokens.ANDAND && checks(node.left, true)) return node.textRange.startOffset
                if (node.operationToken == KtTokens.OROR && checks(node.left, false)) return node.textRange.startOffset
            }
            is KtBlockExpression -> for (st in node.statements) {
                if (st === child) break
                val guard = st as? KtIfExpression ?: continue
                if (guard.`else` == null && branchAlwaysJumps(guard.then) && checks(guard.condition, false)) return st.textRange.startOffset
            }
            // The use's own declaration scope ends the search: a check further out is about another value.
            is KtClassOrObject, is KtFile -> return null
        }
        child = node
        node = node.parent
    }
    return null
}
