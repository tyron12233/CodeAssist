package dev.ide.lang.kotlin.resolve

import dev.ide.kotlin.syntax.psi.KtBinaryExpression
import dev.ide.kotlin.syntax.psi.KtPostfixExpression
import dev.ide.kotlin.syntax.psi.KtIfExpression
import dev.ide.kotlin.syntax.psi.KtWhenEntry
import dev.ide.kotlin.syntax.psi.KtWhenExpression
import dev.ide.kotlin.syntax.psi.KtTryExpression
import dev.ide.kotlin.syntax.psi.KtCatchClause
import dev.ide.kotlin.syntax.psi.KtReturnExpression
import dev.ide.kotlin.syntax.psi.KtThrowExpression
import dev.ide.kotlin.syntax.psi.KtBreakExpression
import dev.ide.kotlin.syntax.psi.KtContinueExpression
import dev.ide.kotlin.syntax.psi.KtBinaryExpressionWithTypeRHS
import dev.ide.kotlin.syntax.psi.KtBlockExpression
import dev.ide.kotlin.syntax.psi.KtCallExpression
import dev.ide.kotlin.syntax.psi.KtClass
import dev.ide.kotlin.syntax.psi.KtElement
import dev.ide.kotlin.syntax.psi.KtExpression
import dev.ide.kotlin.syntax.psi.KtFunctionLiteral
import dev.ide.kotlin.syntax.psi.KtIsExpression
import dev.ide.kotlin.syntax.psi.KtLambdaArgument
import dev.ide.kotlin.syntax.psi.KtLambdaExpression
import dev.ide.kotlin.syntax.psi.KtNameReferenceExpression
import dev.ide.kotlin.syntax.psi.KtNamedFunction
import dev.ide.kotlin.syntax.psi.KtParenthesizedExpression
import dev.ide.kotlin.syntax.psi.KtQualifiedExpression
import dev.ide.kotlin.syntax.psi.KtTokens
import dev.ide.kotlin.syntax.psi.KtValueArgument
import dev.ide.kotlin.syntax.psi.KtValueArgumentList
import dev.ide.lang.kotlin.parse.KotlinDomNode
import dev.ide.lang.kotlin.symbols.KotlinSymbol
import dev.ide.lang.kotlin.symbols.KotlinType
import dev.ide.lang.resolve.SymbolKind

/**
 * Type variables NOTHING fixes, read across nested calls the way the compiler's shared constraint system sees
 * them: `id(materialize())` passes one free variable into another, so neither has a type; `foo().bar()`
 * completes `foo()` on its own (a receiver is resolved before its selector), so whatever its arguments leave
 * open stays open; `val x = run { materialize() }` returns a lambda result nothing constrains.
 *
 * Two directions. [unconstrainedOwnVariables] looks DOWN: a callee's own type parameters that neither an
 * explicit type argument, the receiver, nor any argument constrains, where an argument that is itself a call
 * returning one of ITS free variables ([uninformativeCall]) says nothing. [freeOutside] looks UP: whether the
 * position of the call gives the variable no expected type, which in an argument slot typed by the OUTER
 * callee's own variable recurses into that callee. Both sides are deliberately narrow (one plain candidate per
 * call, no bound naming another type parameter, a short recursion), so a shape it does not understand is
 * read as constrained: this only ever adds a report the compiler also makes.
 */

private const val MAX_FREE_DEPTH = 6

/**
 * A generic callee's signature as DECLARED, its own type parameters marked as variables. A symbol reaches the
 * resolver with its receiver's type arguments already substituted, which for a source declaration can erase a
 * variable from a parameter (a bounded one, or one sharing its name with the class's own, as in `class
 * BiType<X, Y> { fun <X> pullXb(x: X) }`), so a source function is read off its PSI instead. Null when the
 * signature cannot be read: a parameter without a written type, a function type parameter shadowing a class
 * one, or a source symbol without its declaration.
 */
private class DeclaredSignature(val names: Set<String>, val params: List<KotlinType?>, val ret: KotlinType?, val receiverNames: Set<String>)

private fun KotlinResolver.declaredSignature(sym: KotlinSymbol): DeclaredSignature? {
    val names = sym.typeParameters.toSet()
    if (!sym.origin.fromSource) {
        return DeclaredSignature(
            names,
            sym.paramTypes.map { service.markTypeParameters(it as? KotlinType, names) },
            service.markTypeParameters(sym.type as? KotlinType, names),
            emptySet(),
        )
    }
    val fn = (sym.declaration() as? KotlinDomNode)?.psi as? KtNamedFunction ?: return null
    if (fn.typeParameters.mapNotNull { it.name }.toSet() != names) return null
    var owner: KtElement? = fn.parent
    while (owner != null) {
        if (owner is KtClass && owner.typeParameters.any { it.name in names }) return null
        owner = owner.parent
    }
    // A type the text parser cannot fully model (a function type with named parameters, say) must not read as
    // one that names no variable: every variable the written text names has to survive the parse.
    fun parse(ref: KtElement?): KotlinType? {
        val t = service.markDeclaredTypeParameters(service.typeFromText(ref?.text ?: return null, fileContext), names)
            ?: return null
        return t.takeIf { names.all { n -> !boundNames(ref, setOf(n)) || mentionsTypeParam(t, n) } }
    }
    val params = fn.valueParameters.map { p -> parse(p.typeReference) ?: return null }
    val ret = when {
        fn.typeReference != null -> parse(fn.typeReference) ?: return null
        fn.hasBlockBody() -> service.typeByFqn("kotlin.Unit")
        else -> service.markTypeParameters(sym.type as? KotlinType, names)
    }
    val receiverNames = names.filterTo(HashSet()) { boundNames(fn.receiverTypeReference, setOf(it)) }
    return DeclaredSignature(names, params, ret, receiverNames)
}

/** The callee's own type parameters nothing in [call] itself constrains (see the file comment). Empty when
 *  the call cannot be judged: explicit type arguments, a spread, a parameter of unknown type. */
internal fun KotlinResolver.unconstrainedOwnVariables(
    sym: KotlinSymbol,
    call: KtCallExpression,
    depth: Int = 0,
): Set<String> {
    if (sym.typeParameters.isEmpty() || call.typeArgumentList != null || depth > MAX_FREE_DEPTH) return emptySet()
    if (call.valueArguments.any { it.getSpreadElement() != null }) return emptySet()
    // A variable the extension receiver names takes its type from whatever the call is made on.
    val sig = declaredSignature(sym) ?: return emptySet()
    val out = LinkedHashSet<String>()
    for (tp in sym.typeParameters) {
        if (tp == sym.receiverTypeParam || sym.receiverTypeArgs.any { mentionsTypeParam(it, tp) }) continue
        if (tp in sig.receiverNames) continue
        out += tp
    }
    call.valueArguments.forEachIndexed { i, arg ->
        if (out.isEmpty()) return out
        val p = sig.params.getOrNull(lambdaParamIndex(call, i, sym)) ?: return emptySet()
        val mentioned = out.filter { mentionsTypeParam(p, it) }
        if (mentioned.isEmpty()) return@forEachIndexed
        val expr = arg.getArgumentExpression()?.let(::unparenthesized) ?: return emptySet()
        when {
            expr is KtLambdaExpression -> out.removeAll(lambdaConstrains(expr, p, mentioned.toSet(), depth).toSet())
            expr is KtCallExpression && uninformativeCall(expr, p, depth + 1) -> {}
            // An unresolved name is an error type, which constrains nothing (`id(unresolved)`).
            expr is KtNameReferenceExpression && p.isTypeParameter && p.typeArguments.isEmpty() && unresolvedBareName(expr) -> {}
            else -> out.removeAll(mentioned.toSet())
        }
    }
    return out
}

/** Whether [e] is a plain name nothing in scope declares: K2 types it as an error type, no constraint at all. */
private fun KotlinResolver.unresolvedBareName(e: KtNameReferenceExpression): Boolean {
    val name = e.getReferencedName()
    if (name.isEmpty() || name == "it" || name == "field" || !name.first().isLetter()) return false
    val off = e.textRange.startOffset
    return localsAt(off, name).isEmpty() && !bareNameResolves(name, off) && !companionMemberInScope(name, off)
}

/** Whether the call [e] contributes nothing proper to a parameter typed [p]: it returns the same SHAPE as [p]
 *  (a variable where [p] has one, the same classifier around them), and every variable of its own in that
 *  result is one nothing in it constrains. Then `p`'s variables only meet `e`'s, which are free themselves:
 *  `id(materialize())`, `foo(bar())` against `Inv<T>`/`Inv<X>`. */
private fun KotlinResolver.uninformativeCall(e: KtCallExpression, p: KotlinType, depth: Int): Boolean {
    if (depth > MAX_FREE_DEPTH) return false
    val sym = resolveCalleeFunction(e) ?: return false
    if (!plainGenericCallee(e, sym)) return false
    val ret = declaredSignature(sym)?.ret ?: return false
    if (!sameShape(ret, p)) return false
    val free = unconstrainedOwnVariables(sym, e, depth)
    return free.isNotEmpty() && sym.typeParameters.filter { mentionsTypeParam(ret, it) }.all { it in free }
}

/** Both types are type variables, or the same classifier whose arguments pair up the same way. */
private fun sameShape(a: KotlinType, b: KotlinType): Boolean {
    if (a.isTypeParameter || b.isTypeParameter) return a.isTypeParameter && b.isTypeParameter && a.typeArguments.isEmpty()
    if (a.qualifiedName != b.qualifiedName || a.typeArguments.size != b.typeArguments.size) return false
    return a.typeArguments.indices.all { i ->
        val x = a.typeArguments[i] as? KotlinType ?: return false
        val y = b.typeArguments[i] as? KotlinType ?: return false
        sameShape(x, y)
    }
}

/**
 * The [mentioned] variables a lambda argument constrains when it fills the parameter [p]. Its RESULT constrains
 * the variables of the functional return, unless the result is a call that says nothing about them
 * ([uninformativeCall]). Its INPUTS constrain theirs only through what the body does with them: written
 * parameter types do, a body that reads a parameter (or runs on the receiver) may (builder inference), and an
 * empty body or one that never touches them does not.
 */
private fun KotlinResolver.lambdaConstrains(
    lambda: KtLambdaExpression,
    p: KotlinType,
    mentioned: Set<String>,
    depth: Int,
): Set<String> {
    val shape = service.functionalShape(p) ?: return mentioned
    val out = HashSet<String>()
    val ret = shape.returnType as? KotlinType
    val inRet = mentioned.filter { ret != null && mentionsTypeParam(ret, it) }
    if (inRet.isNotEmpty()) {
        val last = lambda.bodyExpression?.statements?.lastOrNull()?.let(::unparenthesized)
        val silent = ret != null && last is KtCallExpression && uninformativeCall(last, ret, depth + 1)
        if (!silent) out += inRet
    }
    val inputs = shape.parameterTypes + listOfNotNull(shape.receiverType)
    val inInputs = mentioned.filter { tp -> inputs.any { mentionsTypeParam(it, tp) } }
    if (inInputs.isNotEmpty() && lambdaReadsItsInputs(lambda, shape.receiverType != null)) out += inInputs
    return out
}

/** Whether a lambda's body can learn anything about its inputs' types: a written parameter type, any
 *  statement at all when it has a receiver (a bare call may be on it), or a read of a parameter or `it`. */
private fun lambdaReadsItsInputs(lambda: KtLambdaExpression, hasReceiver: Boolean): Boolean {
    val params = lambda.valueParameters
    if (params.any { it.typeReference != null || it.destructuringDeclaration != null }) return true
    val statements = lambda.bodyExpression?.statements.orEmpty()
    if (statements.isEmpty()) return false
    if (hasReceiver) return true
    val names = if (params.isEmpty()) setOf("it") else params.mapNotNull { it.name }.toSet()
    var reads = false
    fun visit(e: KtElement) {
        if (reads) return
        if (e is KtNameReferenceExpression && e.getReferencedName() in names) { reads = true; return }
        var c = e.firstChild
        while (c != null && !reads) { (c as? KtElement)?.let(::visit); c = c.nextSibling }
    }
    statements.forEach(::visit)
    return reads
}

/**
 * Whether nothing around [call] constrains its callee's variable [tp]: the call is an explicit RECEIVER (the
 * compiler completes a receiver before resolving what is called on it), the operand of `is`, the left side of
 * an equality, the operand of a cast the callee cannot take its type from, an argument filling a parameter
 * typed by a variable of the outer call that is free in turn, or the result of a lambda whose return type is
 * such a variable. A variable the callee's result does not mention is out of reach of every position.
 */
internal fun KotlinResolver.freeOutside(
    call: KtCallExpression,
    sym: KotlinSymbol,
    tp: String,
    depth: Int = 0,
): Boolean {
    if (depth > MAX_FREE_DEPTH) return false
    val sig = declaredSignature(sym) ?: return false
    val ret = sig.ret ?: return false
    if (!mentionsTypeParam(ret, tp)) return true
    val q = (call.parent as? KtQualifiedExpression)?.takeIf { it.selectorExpression === call }
    var e: KtExpression = q ?: call
    while (e.parent is KtParenthesizedExpression) e = e.parent as KtExpression
    // A branch, a `!!` operand or an elvis operand whose construct is a discarded statement has no expected type
    // either, provided every other branch says nothing about the result (`if (b) id(x) else id(y)`).
    liftedStatement(e)?.let { return true }
    return when (val parent = e.parent) {
        is KtQualifiedExpression -> parent.receiverExpression === e
        is KtIsExpression -> parent.leftHandSide === e
        // Both operands of an equality are resolved independently (K2 resolves them ContextIndependent).
        // On the right the operand is expected to be `Any?`: that fixes a variable that IS the result (`"" ==
        // materialize()`) or has a declared bound (`child == child.transform()` for `<T : Base>`), but not one
        // nested in it (`z == newA()` for `A<T>`).
        is KtBinaryExpression -> parent.operationToken in EQUALITY_TOKENS && (parent.left === e ||
            (parent.right === e && unboundedSourceTypeParameter(sym, tp) && !(ret.isTypeParameter && ret.qualifiedName == tp)))
        is KtBinaryExpressionWithTypeRHS -> parent.left === e && !castGivesExpectedType(sym, sig)
        is KtValueArgument -> argumentSlotFree(parent, e, depth)
        is KtBlockExpression -> lambdaResultFree(parent, e, depth) || discardedOrUntypedPosition(e)
        else -> discardedOrUntypedPosition(e)
    }
}

/** Whether [tp] of the source function [sym] is declared with no bound at all. */
private fun unboundedSourceTypeParameter(sym: KotlinSymbol, tp: String): Boolean {
    val fn = (sym.declaration() as? KotlinDomNode)?.psi as? KtNamedFunction ?: return false
    val p = fn.typeParameters.firstOrNull { it.name == tp } ?: return false
    return p.extendsBound == null && fn.typeConstraints.none { it.subjectTypeParameterName?.getReferencedName() == tp }
}

private val EQUALITY_TOKENS = setOf(KtTokens.EQEQ, KtTokens.EXCLEQ, KtTokens.EQEQEQ, KtTokens.EXCLEQEQEQ)

/**
 * The statement [e] is the value of, through `!!`, an elvis operand, an `if`/`when` branch and a `try`/`catch`
 * block's last statement, when that statement's value is discarded; null when [e] is not lifted at all or the
 * construct is used as a value. K2 resolves such a construct as a synthetic call (`if<T>(a, b)`) whose sibling
 * branches feed the same variable, so each sibling must be silent: absent, a jump, or [silentBranch].
 */
private fun KotlinResolver.liftedStatement(e: KtExpression): KtExpression? {
    var cur = e
    var lifted = false
    while (true) {
        val p = cur.parent
        val pp = p?.parent
        val next: KtExpression? = when {
            p is KtParenthesizedExpression -> p
            p is KtPostfixExpression && p.operationToken == KtTokens.EXCLEXCL && p.baseExpression === cur -> p
            p is KtBinaryExpression && p.operationToken == KtTokens.ELVIS ->
                p.takeIf { listOf(p.left, p.right).all { it === cur || silentBranch(it) } }
            pp is KtIfExpression && (pp.then === cur || pp.`else` === cur) ->
                pp.takeIf { listOf(pp.then, pp.`else`).all { it === cur || silentBranch(it) } }
            p is KtWhenEntry && p.expression === cur -> (pp as? KtWhenExpression)
                ?.takeIf { w -> w.entries.all { it.expression === cur || silentBranch(it.expression) } }
            p is KtBlockExpression && p.statements.lastOrNull() === cur -> {
                val owner = p.parent
                val ownerParent = owner?.parent
                when {
                    owner is KtTryExpression && owner.tryBlock === p -> owner.takeIf { triesSilent(it, p) }
                    owner is KtCatchClause && ownerParent is KtTryExpression -> ownerParent.takeIf { triesSilent(it, p) }
                    ownerParent is KtIfExpression && (ownerParent.then === p || ownerParent.`else` === p) ->
                        ownerParent.takeIf { i -> listOf(i.then, i.`else`).all { it === p || silentBranch(it) } }
                    owner is KtWhenEntry && owner.expression === p -> (ownerParent as? KtWhenExpression)
                        ?.takeIf { w -> w.entries.all { it.expression === p || silentBranch(it.expression) } }
                    else -> null
                }
            }
            else -> null
        }
        if (next == null) break
        cur = next
        lifted = true
    }
    if (!lifted) return null
    val block = cur.parent as? KtBlockExpression ?: return null
    if (block.statements.lastOrNull() === cur && block.parent !is KtNamedFunction) return null
    return cur.takeIf { discardedOrUntypedPosition(it) }
}

private fun KotlinResolver.triesSilent(t: KtTryExpression, from: KtBlockExpression): Boolean =
    (listOf(t.tryBlock) + t.catchClauses.mapNotNull { it.catchBody }).all { b ->
        b === from || silentBranch(b)
    }

/** A branch value that gives the construct's type nothing: missing, a jump, or a call to a plain generic callee
 *  whose result is one of its own variables nothing in the call constrains (`id(unresolved)`, `materialize()`). */
private fun KotlinResolver.silentBranch(branch: KtExpression?): Boolean {
    val v = when (branch) {
        null -> return true
        is KtBlockExpression -> branch.statements.lastOrNull() ?: return true
        else -> branch
    }.let(::unparenthesized)
    if (v is KtReturnExpression || v is KtThrowExpression || v is KtBreakExpression || v is KtContinueExpression) return true
    val inner = (v as? KtPostfixExpression)?.takeIf { it.operationToken == KtTokens.EXCLEXCL }?.baseExpression?.let(::unparenthesized) ?: v
    val call = inner as? KtCallExpression ?: return false
    val sym = resolveCalleeFunction(call) ?: return false
    if (!plainGenericCallee(call, sym)) return false
    val ret = declaredSignature(sym)?.ret ?: return false
    if (!ret.isTypeParameter || ret.typeArguments.isNotEmpty()) return false
    return ret.qualifiedName in unconstrainedOwnVariables(sym, call)
}

/** The compiler takes a cast's type as the expected type of a call to a function with ONE type parameter
 *  that is exactly its return type and that nothing else in its signature names (`foo() as String`). */
private fun KotlinResolver.castGivesExpectedType(sym: KotlinSymbol, sig: DeclaredSignature): Boolean {
    if (sym.typeParameters.size != 1) return false
    val tp = sym.typeParameters[0]
    val ret = sig.ret ?: return false
    if (!ret.isTypeParameter || ret.qualifiedName != tp) return false
    if (sig.params.any { it == null || mentionsTypeParam(it, tp) }) return false
    return tp !in sig.receiverNames && tp != sym.receiverTypeParam && sym.receiverTypeArgs.none { mentionsTypeParam(it, tp) }
}

/** An argument [e] whose parameter in the outer call is exactly one of the outer callee's own variables, which
 *  the outer call leaves free in turn. */
private fun KotlinResolver.argumentSlotFree(arg: KtValueArgument, e: KtExpression, depth: Int): Boolean {
    if (arg.getArgumentExpression() !== e) return false
    val outer = (arg.parent as? KtValueArgumentList)?.parent as? KtCallExpression ?: return false
    val index = outer.valueArguments.indexOf(arg).takeIf { it >= 0 } ?: return false
    return outerVariableFree(outer, index, depth) { it }
}

/** The last statement [e] of a lambda's body, whose functional return in the outer call is one of the outer
 *  callee's own variables, free in turn. */
private fun KotlinResolver.lambdaResultFree(block: KtBlockExpression, e: KtExpression, depth: Int): Boolean {
    if (block.statements.lastOrNull() !== e) return false
    val lambda = (block.parent as? KtFunctionLiteral)?.parent as? KtLambdaExpression ?: return false
    val arg = lambda.parent as? KtValueArgument ?: return false
    val outer = when (arg) {
        is KtLambdaArgument -> arg.parent as? KtCallExpression
        else -> (arg.parent as? KtValueArgumentList)?.parent as? KtCallExpression
    } ?: return false
    val index = outer.valueArguments.indexOf(arg).takeIf { it >= 0 } ?: return false
    return outerVariableFree(outer, index, depth) { p -> service.functionalShape(p)?.returnType as? KotlinType }
}

/** Whether the parameter the [index]th argument of [outer] fills, narrowed by [slot], is a bare own variable of
 *  the outer callee that nothing constrains there either. */
private inline fun KotlinResolver.outerVariableFree(
    outer: KtCallExpression,
    index: Int,
    depth: Int,
    slot: (KotlinType) -> KotlinType?,
): Boolean {
    val sym = resolveCalleeFunction(outer) ?: return false
    if (!plainGenericCallee(outer, sym)) return false
    val names = sym.typeParameters.toSet()
    val p = declaredSignature(sym)?.params?.getOrNull(lambdaParamIndex(outer, index, sym)) ?: return false
    val v = slot(p) ?: return false
    if (!v.isTypeParameter || v.qualifiedName !in names || v.typeArguments.isNotEmpty()) return false
    if (v.qualifiedName !in unconstrainedOwnVariables(sym, outer, depth + 1)) return false
    return freeOutside(outer, sym, v.qualifiedName, depth + 1)
}

/**
 * Whether [sym] is a callee whose type parameters this analysis can read: the call's only candidate, with no
 * bound naming another type parameter (inference can reach one variable through another's bound), and, from a
 * library, a plain top-level or static function rather than an extension or a member reached through a value.
 */
internal fun KotlinResolver.plainGenericCallee(call: KtCallExpression, sym: KotlinSymbol): Boolean {
    if (sym.typeParameters.isEmpty()) return false
    // The same declaration can be listed twice (from the file and from the project index).
    val targets = runCatching { callTargets(call) }.getOrDefault(emptyList())
        .filter { it.kind == SymbolKind.METHOD || it.kind == SymbolKind.CONSTRUCTOR }
        .distinctBy { t ->
            val at = ((t.declaration() as? KotlinDomNode)?.psi)?.textRange?.startOffset
            if (t.origin.fromSource && at != null) Pair(t.origin.file?.toString(), at) else t
        }
    if (targets.size != 1 || targets[0].typeParameters != sym.typeParameters) return false
    if (sym.typeParamBoundNames.any { it != null }) return false
    val q = (call.parent as? KtQualifiedExpression)?.takeIf { it.selectorExpression === call }
    if (!sym.origin.fromSource) {
        // An erased binary bound may hide a path from one type parameter to another, so only a lone one.
        if (sym.typeParameters.size != 1) return false
        if (sym.kind != SymbolKind.METHOD || sym.isExtension || sym.receiverTypeFqn != null) return false
        if (q != null && !isTypeReceiver(q.receiverExpression)) return false
        return sym.paramTypes.none { it == null }
    }
    val fn = (sym.declaration() as? KotlinDomNode)?.psi as? KtNamedFunction ?: return false
    if (fn.typeConstraints.isNotEmpty()) return false
    // Every type parameter the bounds could name: the function's own and its enclosing classes'.
    val typeParamNames = HashSet<String>()
    fn.typeParameters.mapNotNullTo(typeParamNames) { it.name }
    var owner: KtElement? = fn.parent
    while (owner != null) {
        if (owner is KtClass) owner.typeParameters.mapNotNullTo(typeParamNames) { it.name }
        owner = owner.parent
    }
    return fn.typeParameters.none { p -> boundNames(p.extendsBound, typeParamNames) }
}

/** Whether the type text under [t] names any of [names] (a written bound such as `List<T>`). */
private fun boundNames(t: KtElement?, names: Set<String>): Boolean {
    if (t == null) return false
    var found = false
    fun rec(e: KtElement) {
        if (found) return
        if (e is KtNameReferenceExpression && e.getReferencedName() in names) { found = true; return }
        var c = e.firstChild
        while (c != null && !found) { (c as? KtElement)?.let(::rec); c = c.nextSibling }
    }
    rec(t)
    return found
}

private fun unparenthesized(e: KtExpression): KtExpression {
    var x = e
    while (x is KtParenthesizedExpression) x = x.expression ?: return x
    return x
}
