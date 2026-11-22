package dev.ide.lang.kotlin.resolve

import dev.ide.kotlin.syntax.psi.KtAnnotationEntry
import dev.ide.kotlin.syntax.psi.KtBinaryExpression
import dev.ide.kotlin.syntax.psi.KtBinaryExpressionWithTypeRHS
import dev.ide.kotlin.syntax.psi.KtIsExpression
import dev.ide.kotlin.syntax.psi.KtQualifiedExpression
import dev.ide.kotlin.syntax.psi.KtThrowExpression
import dev.ide.kotlin.syntax.psi.KtBlockExpression
import dev.ide.kotlin.syntax.psi.KtCallExpression
import dev.ide.kotlin.syntax.psi.KtDoWhileExpression
import dev.ide.kotlin.syntax.psi.KtElement
import dev.ide.kotlin.syntax.psi.KtExpression
import dev.ide.kotlin.syntax.psi.KtFunctionLiteral
import dev.ide.kotlin.syntax.psi.KtIfExpression
import dev.ide.kotlin.syntax.psi.KtLambdaExpression
import dev.ide.kotlin.syntax.psi.KtNameReferenceExpression
import dev.ide.kotlin.syntax.psi.KtNamedFunction
import dev.ide.kotlin.syntax.psi.KtPostfixExpression
import dev.ide.kotlin.syntax.psi.KtPrefixExpression
import dev.ide.kotlin.syntax.psi.KtProperty
import dev.ide.kotlin.syntax.psi.KtPropertyAccessor
import dev.ide.kotlin.syntax.psi.KtReturnExpression
import dev.ide.kotlin.syntax.psi.KtTokens
import dev.ide.kotlin.syntax.psi.KtValueArgument
import dev.ide.kotlin.syntax.psi.KtValueArgumentList
import dev.ide.kotlin.syntax.psi.KtWhenConditionWithExpression
import dev.ide.kotlin.syntax.psi.KtWhenExpression
import dev.ide.kotlin.syntax.psi.KtWhileExpression
import dev.ide.kotlin.syntax.psi.getStrictParentOfType
import dev.ide.lang.kotlin.symbols.KotlinType

/** Expected type at a position: the target type a return or argument slot demands. */

/**
 * The type the context at [offset] expects an expression to have, or null when unconstrained: a typed
 * property initializer (`val x: T = ‹here›`), a `return ‹here›` / expression body of a typed function, a
 * call-argument slot (positional or named), and a boolean condition (`if`/`while`/`do-while`, `&&`/`||`,
 * `!`). Powers expected-type-aware completion (rank matches first, offer enum constants + booleans).
 * A lambda's result, the operand of `!!` and an argument for a parameter typed by the callee's own variable
 * take what the context expects of the lambda or the call around them; a statement whose value nobody reads
 * expects nothing.
 */
fun KotlinResolver.expectedTypeAt(offset: Int): KotlinType? = expectedTypeWalk(null, elementAt(offset))

/** The type the context expects of the expression [e] itself (the walk starts at its parent). */
internal fun KotlinResolver.expectedTypeOf(e: KtElement): KotlinType? = expectedTypeWalk(e, e.parent)

private fun KotlinResolver.expectedTypeWalk(from: KtElement?, start: KtElement?): KotlinType? {
    val offset = (from ?: start)?.textRange?.startOffset ?: return null
    var child: KtElement? = from
    var node: KtElement? = start
    while (node != null) {
        when (node) {
            is KtProperty ->
                if (child != null && child === node.initializer)
                    return node.typeReference?.text?.let { service.typeFromText(it, fileContext, enclosingClassFqnOf(node)) }

            is KtNamedFunction ->
                if (child != null && child === node.bodyExpression && !node.hasBlockBody())
                    return node.typeReference?.text?.let { service.typeFromText(it, fileContext, enclosingClassFqnOf(node)) }
                // A block body, a parameter, a default value: what surrounds the declaration expects nothing of
                // them. Walking on handed a local function's statements the type of whatever encloses it.
                else if (child != null) return null

            // A statement that is not the block's last is a value nobody reads, whatever the block's own
            // expected type: `val x: Int = if (c) { log(); 1 } else 2` expects nothing of `log()`.
            is KtBlockExpression ->
                if (child != null && node.statements.lastOrNull() !== child) return null

            // The last statement of a lambda is its result, so it is expected to have the lambda's return type.
            // Walking on instead reached the argument the lambda fills, and handed the result the whole
            // FUNCTION type (`run { make() }` read `make()` as expected to be a `() -> R`).
            is KtFunctionLiteral -> if (child == null || child !== node.bodyExpression) return null
            is KtLambdaExpression -> return if (child is KtFunctionLiteral) expectedLambdaResultType(node) else null

            // `e!!` is the compiler's `checkNotNull<K>(e: K?): K`, so `e` is expected to be the nullable form of
            // what `e!!` is: `doList(emptyNullableListOfA()!!)` asks a `List<Int>?` of the inner call.
            is KtPostfixExpression ->
                if (child != null && node.operationToken == KtTokens.EXCLEXCL && child === node.baseExpression)
                    return expectedTypeWalk(node, node.parent)?.withNullable(true)

            is KtReturnExpression ->
                if (child === node.returnedExpression) return enclosingFunctionReturnType(node)

            is KtValueArgument -> return expectedArgType(node)
            // A `when`-entry condition with a SUBJECT (`when (color) { █ }`) expects the subject's type — so
            // value completion offers its enum constants / companion constants. A subjectless `when` has
            // Boolean conditions; left to the generic path (offering true/false there is low value).
            is KtWhenConditionWithExpression ->
                node.getStrictParentOfType<KtWhenExpression>()?.subjectExpression?.let { s ->
                    inferType(
                        s
                    )?.let { return it }
                }
            // A condition is wrapped in a container node, so match by range rather than child identity.
            is KtIfExpression -> if (node.condition?.textRange?.contains(offset) == true) return service.typeByFqn(
                "kotlin.Boolean"
            )

            is KtWhileExpression -> if (node.condition?.textRange?.contains(offset) == true) return service.typeByFqn(
                "kotlin.Boolean"
            )

            is KtDoWhileExpression -> if (node.condition?.textRange?.contains(offset) == true) return service.typeByFqn(
                "kotlin.Boolean"
            )

            is KtPrefixExpression ->
                if (node.operationToken == KtTokens.EXCL) return service.typeByFqn("kotlin.Boolean")

            // Positions the outer expected type does NOT reach. A call's RECEIVER (`val s: String = a.b().c()`
            // expects nothing of `a.b()`), a `throw`'s operand (it expects a Throwable whatever the declaration
            // around it says: `val b: Unit = throw a()` must not bind `a`'s `T` to Unit), and the operand of
            // `as`/`is`. Walking on past them handed every such operand the declaration's type.
            is KtQualifiedExpression -> if (child != null && child === node.receiverExpression) return null
            is KtThrowExpression ->
                if (child != null && child === node.thrownExpression) return service.typeByFqn("kotlin.Throwable")
            is KtBinaryExpressionWithTypeRHS -> if (child != null && child === node.left) return null
            is KtIsExpression -> return null

            is KtBinaryExpression -> when {
                node.operationToken == KtTokens.ANDAND || node.operationToken == KtTokens.OROR ->
                    return service.typeByFqn("kotlin.Boolean")
                // The right side of a plain assignment expects the TARGET's type, exactly as a typed initializer
                // does. Without this, `gv = findViewById(R.id.game)` (a `lateinit var gv: GameView`) left
                // `<T : View>` with nothing but its bound, read as View, and flagged "inferred type is View but
                // GameView was expected" on every view lookup in an Activity, while `val gv: GameView =
                // findViewById(...)` was fine. Augmented assignments (`+=`) are operator calls, not this.
                node.operationToken == KtTokens.EQ && child != null && child === node.right ->
                    return node.left?.let { assignmentTargetDeclaredType(it) }
                // An elvis passes the expectation on to both sides; any other operator (`+`, `==`, `in`, an
                // infix call) is a call whose operands are its arguments, not the result.
                node.operationToken == KtTokens.ELVIS -> {}
                else -> return null
            }
        }
        child = node
        node = node.parent
    }
    return null
}

/**
 * The DECLARED type of an assignment target, which is not the same as its type at the assignment.
 *
 * `inferType` applies smart casts, and a variable is routinely assigned inside a narrowing of itself:
 * `var p = e.parent; while (p is Paren) { p = p.parent }` advances the walk by assigning the wider declared
 * type back, which is legal Kotlin and simply ends the smart cast. Reading the narrowed type as the target's
 * type made that read "inferred type is KtElement? but KtParenthesizedExpression was expected" -- a complaint
 * about the only line that could ever terminate the loop.
 *
 * A local's declaration type is taken straight from its binding; anything else (a property, a qualified
 * target) keeps the inferred type, since Kotlin does not smart-cast a mutable property anyway.
 */
internal fun KotlinResolver.assignmentTargetDeclaredType(left: KtExpression): KotlinType? {
    if (left is KtNameReferenceExpression) {
        val name = left.getReferencedName()
        localsAt(left.textRange.startOffset, name).firstOrNull()
            ?.let { return it.type as? KotlinType }
    }
    return inferType(left)
}

internal fun KotlinResolver.enclosingFunctionReturnType(from: KtElement): KotlinType? {
    var node: KtElement? = from.parent
    while (node != null) {
        if (node is KtNamedFunction) {
            node.typeReference?.text?.let { return service.typeFromText(it, fileContext) }
            // No declared type: a BLOCK body's return type is `Unit` (so `return f()` there expects Unit, which
            // is what coerces a lambda-bound result, see [coerceLambdaReturnVarToUnit]); an EXPRESSION body's
            // is inferred FROM the returned expression, so the context demands nothing.
            return if (node.hasBlockBody()) service.typeByFqn("kotlin.Unit") else null
        }
        if (node is KtLambdaExpression) return null // a return@label leaves the lambda's type to inference
        // A getter's block body returns its property's declared type (`val text: String get() { return … }`).
        if (node is KtPropertyAccessor) {
            if (!node.isGetter || !node.hasBlockBody()) return null
            val type = (node.typeReference ?: node.property?.typeReference)?.text ?: return null
            return service.typeFromText(type, fileContext)
        }
        node = node.parent
    }
    return null
}

internal fun KotlinResolver.expectedArgType(arg: KtValueArgument): KotlinType? {
    val argList = arg.parent as? KtValueArgumentList ?: return null
    // An ANNOTATION argument (`@Foo(mode = <caret>)`): resolve against the annotation's parameter types so an
    // enum-typed argument offers its constants — the annotation's arg list parent is a KtAnnotationEntry, not a
    // KtCallExpression, so the call path below can't reach it.
    (argList.parent as? KtAnnotationEntry)?.let { entry ->
        val params = annotationParameters(entry)
        val argName = arg.getArgumentName()?.asName?.identifier
        val p = if (argName != null) params.firstOrNull { it.name == argName }
        else params.getOrNull(argList.arguments.indexOf(arg))
        return p?.type
    }
    val call = argList.parent as? KtCallExpression ?: return null
    // Overloads that can take this many arguments come first. Without that the first overload wins, whatever
    // its arity: `stream.collect(Collectors.toList())` read its argument against the three-argument
    // `collect(Supplier<R>, …)`, so `toList()` got no `T` from the parameter it really fills and typed as
    // `Collector<Object, …>`, and the whole chain off `collect` became `List<Object>`.
    val targets = callTargets(call).sortedBy { if (acceptsArgCount(it, call.valueArguments.size)) 0 else 1 }
    if (targets.isEmpty()) return null
    val argName = arg.getArgumentName()?.asName?.identifier
    if (argName != null) {
        targets.forEach { s ->
            val i = s.paramNames.indexOf(argName)
            if (i >= 0) (s.paramTypes.getOrNull(i) as? KotlinType)?.let { return it }
        }
        return null
    }
    val index = argList.arguments.indexOf(arg)
    targets.forEach { s ->
        // A positional argument past a vararg still fills the vararg, not the parameter after it.
        val paramIndex = if (s.varargParamIndex in 0..<index) s.varargParamIndex else index
        (s.paramTypes.getOrNull(paramIndex) as? KotlinType)?.let {
            val vararg = paramIndex == s.varargParamIndex && arg.getSpreadElement() == null
            instantiatedFromSiblingArguments(s, call, arg, it, vararg)?.let { t -> return t }
            return instantiatedFromCallExpectation(s, call, it, vararg = vararg)
        }
    }
    return null
}

/**
 * A parameter type [p] of [s] naming the callee's own type variables, instantiated from the OTHER arguments of
 * [call] when they pin every variable it mentions: `assign2(x, jsObject { … })` with `fun <K> assign2(dest: K,
 * src: K?)` expects a `DIV?` of the second argument once `x: DIV` fixed `K`. A variable bounded by another
 * (`R : K`) passes its binding up as that one's lower bound, which fixes it when nothing else does (K2's
 * fixation of `K` to `DIV` in `fun <K, R : K> assign(dest: R, vararg src: K?)`). Lambdas are skipped (they are
 * typed from this slot, not the other way round). Null when some variable stays open.
 */
private fun KotlinResolver.instantiatedFromSiblingArguments(
    s: dev.ide.lang.kotlin.symbols.KotlinSymbol,
    call: KtCallExpression,
    arg: KtValueArgument,
    p: KotlinType,
    vararg: Boolean,
): KotlinType? {
    if (s.typeParameters.isEmpty()) return null
    val names = s.typeParameters.toSet()
    val param = service.markTypeParameters(p, names) ?: return null
    if (names.none { mentionsTypeParam(param, it) }) return null
    if (call.valueArguments.any { it.getArgumentName() != null || it.getSpreadElement() != null }) return null
    val bindings = HashMap<String, dev.ide.lang.resolve.TypeRef>()
    for ((j, other) in call.valueArguments.withIndex()) {
        if (other === arg) continue
        val e = other.getArgumentExpression() ?: continue
        if (e is dev.ide.kotlin.syntax.psi.KtLambdaExpression) continue
        val pj = if (s.varargParamIndex in 0..<j) s.varargParamIndex else j
        val declared = service.markTypeParameters(s.paramTypes.getOrNull(pj) as? KotlinType, names) ?: continue
        val actual = inferType(e)?.takeIf { !it.isTypeParameter } ?: continue
        val elem = if (pj == s.varargParamIndex && declared.qualifiedName == "kotlin.Array") declared.typeArguments.singleOrNull() as? KotlinType ?: continue else declared
        unify(elem, actual, bindings)
    }
    // `R : K` with `R` bound: `K` is at least that. A same-file symbol carries no bounds, so they are read off
    // its declaration then.
    val fn = (s.declaration() as? dev.ide.lang.kotlin.parse.KotlinDomNode)?.psi as? dev.ide.kotlin.syntax.psi.KtNamedFunction
    s.typeParameters.forEachIndexed { i, tp ->
        val boundName = s.typeParamBoundNames.getOrNull(i)
            ?: (s.typeParameterBounds.getOrNull(i) as? KotlinType)?.takeIf { it.typeArguments.isEmpty() }?.qualifiedName
            ?: fn?.typeParameters?.firstOrNull { it.name == tp }?.extendsBound?.text?.trim()
            ?: fn?.typeConstraints?.firstOrNull { it.subjectTypeParameterName?.getReferencedName() == tp }?.boundTypeReference?.text?.trim()
            ?: return@forEachIndexed
        if (boundName in names && boundName !in bindings) bindings[tp]?.let { bindings[boundName] = it }
    }
    if (bindings.isEmpty() || bindings.values.any { (it as? KotlinType)?.isTypeParameter != false }) return null
    val element = if (vararg && param.qualifiedName == "kotlin.Array") param.typeArguments.singleOrNull() as? KotlinType ?: return null else param
    val sub = service.substitute(element, bindings) as? KotlinType ?: return null
    return if (names.any { mentionsTypeParam(sub, it) }) null else sub.withProjection("")
}

/**
 * A parameter type [p] of [s] that names the callee's own type variables, instantiated from what the context
 * expects of the whole [call]: `val l: List<Int> = id(make())` gives `id`'s `T` the value `List<Int>`, so its
 * argument is expected to be a `List<Int>` too. This is the part of the compiler's joint constraint system an
 * expected type can carry downward into a nested generic call; without it the inner call fixed its variables
 * from their declared bounds alone and the outer one then mismatched. Applied only when the expectation pins
 * every variable [p] mentions, so a partly free parameter keeps the raw type it always had. A vararg slot
 * expects one element, not the array.
 */
private fun KotlinResolver.instantiatedFromCallExpectation(
    s: dev.ide.lang.kotlin.symbols.KotlinSymbol,
    call: KtCallExpression,
    p: KotlinType,
    vararg: Boolean,
): KotlinType {
    if (s.typeParameters.isEmpty()) return p
    // A symbol read back from the index may name its own type parameters without marking them as such.
    val names = s.typeParameters.toSet()
    val param = service.markTypeParameters(p, names) ?: return p
    val ret = service.markTypeParameters(s.type as? KotlinType, names) ?: return p
    if (names.none { mentionsTypeParam(param, it) } || names.none { mentionsTypeParam(ret, it) }) return p
    val outer = expectedTypeOf(call) ?: return p
    if (outer.isTypeParameter || !service.isKnownType(outer.qualifiedName)) return p
    val bindings = HashMap<String, dev.ide.lang.resolve.TypeRef>()
    unify(ret, outer, bindings)
    if (bindings.values.any { (it as? KotlinType)?.isTypeParameter != false }) return p
    val element = if (vararg && param.qualifiedName == "kotlin.Array") param.typeArguments.singleOrNull() as? KotlinType ?: return p else param
    val sub = service.substitute(element, bindings) as? KotlinType ?: return p
    return if (names.any { mentionsTypeParam(sub, it) }) p else sub.withProjection("")
}

/**
 * The type a lambda's RESULT is expected to have: its expected functional return type when that is a real
 * type, and nothing under coercion to Unit. A return typed by a variable the callee also returns (`run`,
 * `let`, `with`, any `fun <R> f(block: () -> R): R`) passes on what the context expects of the call itself, as
 * the compiler's shared constraint system does: `val b: Base = myRun { decode() ?: d }` expects a `Base` of
 * the elvis, which is what lets `decode`'s `T` be fixed to it.
 */
private fun KotlinResolver.expectedLambdaResultType(lambda: KtLambdaExpression): KotlinType? {
    val ret = expectedLambdaShape(lambda)?.returnType as? KotlinType ?: return null
    val call = enclosingCallAndParamIndex(lambda)?.first
    val sym = call?.let { resolveCalleeFunction(it) ?: constructorCallee(it) }
    // A symbol read back from the index may name its own type parameters without marking them as such.
    fun isOwnVariable(t: KotlinType) =
        t.isTypeParameter || (sym != null && t.typeArguments.isEmpty() && t.qualifiedName in sym.typeParameters)
    if (!isOwnVariable(ret)) {
        if (ret.isTypeParameter || mentionsAnyVariable(ret, sym?.typeParameters.orEmpty())) return null
        return ret.takeUnless { coercesResultToUnit(it) || ret.projection == "*" }
    }
    if (ret.nullable || call == null || sym == null) return null
    val callRet = sym.type as? KotlinType ?: return null
    if (!isOwnVariable(callRet) || callRet.nullable || callRet.qualifiedName != ret.qualifiedName) return null
    return expectedTypeOf(call)?.takeUnless { it.isTypeParameter }
}

/** Whether [t] is, or nests, a type parameter: one marked as such, or one named in [names]. */
private fun mentionsAnyVariable(t: KotlinType, names: List<String>): Boolean {
    if (t.isTypeParameter || (t.typeArguments.isEmpty() && t.qualifiedName in names)) return true
    return t.typeArguments.any { it is KotlinType && mentionsAnyVariable(it, names) }
}

/** Whether [sym] can be called with [count] arguments: every parameter filled, or the missing ones have
 *  defaults, or the extras go to its vararg. */
private fun acceptsArgCount(sym: dev.ide.lang.kotlin.symbols.KotlinSymbol, count: Int): Boolean {
    val n = sym.paramTypes.size
    if (count == n) return true
    if (sym.varargParamIndex >= 0) return count >= n - 1
    if (count > n) return false
    return (count until n).all { sym.paramHasDefault.getOrNull(it) == true }
}
