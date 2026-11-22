package dev.ide.lang.kotlin.resolve

import dev.ide.kotlin.syntax.psi.KtBlockExpression
import dev.ide.kotlin.syntax.psi.KtCallExpression
import dev.ide.kotlin.syntax.psi.KtCallableReferenceExpression
import dev.ide.kotlin.syntax.psi.KtClass
import dev.ide.kotlin.syntax.psi.KtClassInitializer
import dev.ide.kotlin.syntax.psi.KtClassOrObject
import dev.ide.kotlin.syntax.psi.KtConstantExpression
import dev.ide.kotlin.syntax.psi.KtElement
import dev.ide.kotlin.syntax.psi.KtExpression
import dev.ide.kotlin.syntax.psi.KtFunctionLiteral
import dev.ide.kotlin.syntax.psi.KtLambdaArgument
import dev.ide.kotlin.syntax.psi.KtLambdaExpression
import dev.ide.kotlin.syntax.psi.KtNameReferenceExpression
import dev.ide.kotlin.syntax.psi.KtNamedFunction
import dev.ide.kotlin.syntax.psi.KtProperty
import dev.ide.kotlin.syntax.psi.KtPropertyAccessor
import dev.ide.kotlin.syntax.psi.KtQualifiedExpression
import dev.ide.kotlin.syntax.psi.KtSecondaryConstructor
import dev.ide.kotlin.syntax.psi.KtTokens
import dev.ide.kotlin.syntax.psi.siblings
import dev.ide.lang.kotlin.parse.KotlinDomNode
import dev.ide.lang.kotlin.symbols.KotlinSymbol
import dev.ide.lang.kotlin.symbols.KotlinSymbolService
import dev.ide.lang.kotlin.symbols.KotlinType
import dev.ide.lang.resolve.SymbolKind
import dev.ide.lang.resolve.TypeRef

/** Type-argument inference and applicability: unification, lower-bound propagation, uninferable type parameters, and missing-argument checks. */

/**
 * Apply `T : R` lower bounds (recorded at receiver binding, see [KotlinSymbolService.bindExtensionReceiver])
 * to a callee's still-free return type parameters. `Result<String>.getOrElse { … }`: T : R, T = String ⇒
 * R ≥ String. When the `onFailure` lambda returns a concrete type, argument inference already bound R (e.g.
 * `{ "x" }` → String); but `{ null }` gives R no type, so without this R stays a raw `R`. Here R is widened to
 * its lower bound (String), made nullable when the lambda body is `null` → `String?` (Kotlin's LUB of
 * `String` and `Nothing?`). A `let { null }` has NO such bound, so it is untouched (stays the lambda result).
 */
internal fun KotlinResolver.applyLowerBounds(
    sym: KotlinSymbol,
    call: KtCallExpression,
    bindings: MutableMap<String, TypeRef>
) {
    if (sym.typeParamLowerBounds.isEmpty()) return
    for ((param, lowerRef) in sym.typeParamLowerBounds) {
        val lower = lowerRef as? KotlinType ?: continue
        val cur = bindings[param] as? KotlinType
        when {
            // Argument inference left R free (its lambda returned `null` → no type) → R = the lower bound,
            // nullable when that lambda body is the `null` literal.
            cur == null || cur.isTypeParameter -> bindings[param] =
                if (returnParamGotNullLambda(sym, call, param)) lower.withNullable(true) else lower
            // R was bound to `Nothing`/`Nothing?` (a `null`/`throw` lambda) → widen to the lower bound.
            cur.qualifiedName == "kotlin.Nothing" -> bindings[param] =
                lower.withNullable(cur.nullable || lower.nullable)

            else -> {} // a concrete lambda result already pinned R (`getOrElse { "x" }` → String) — keep it
        }
    }
}

/** Whether [call] passes a lambda — to the parameter whose functional return type is [param] — whose body is
 *  the `null` literal (so [param] should be nullable). */
internal fun KotlinResolver.returnParamGotNullLambda(
    sym: KotlinSymbol,
    call: KtCallExpression,
    param: String
): Boolean {
    call.valueArguments.forEachIndexed { i, arg ->
        val lambda = arg.getArgumentExpression() as? KtLambdaExpression ?: return@forEachIndexed
        val pt = (sym.paramTypes.getOrNull(lambdaParamIndex(call, i, sym)) ?: sym.paramTypes.lastOrNull()) as? KotlinType
            ?: return@forEachIndexed
        val ret = service.functionalShape(pt)?.returnType as? KotlinType
        if (ret?.isTypeParameter == true && ret.qualifiedName == param) {
            val last = lambda.bodyExpression?.statements?.lastOrNull()
            if (last is KtConstantExpression && last.text.trim() == "null") return true
        }
    }
    return false
}

/**
 * Coercion to Unit ([coercesResultToUnit]) applied to the type ARGUMENT a lambda's result bound: when the
 * context expects the call's value to be `Unit` and the callee returns exactly its own type parameter `R`,
 * bound from a lambda argument's result (`fun <R> coroutineScope(block: suspend CoroutineScope.() -> R): R`),
 * the block's last expression is discarded and `R` is `Unit`. So `suspend fun main(): Unit = coroutineScope
 * { launch { … } }` types as `Unit`, which is valid Kotlin, where binding `R` from the trailing `launch`
 * reported the whole body as a `Job`-vs-`Unit` mismatch. Only a LAMBDA-bound return variable is rewritten, so
 * a value argument's binding (`fun <T> id(t: T): T`, `val u: Unit = id(5)`) still surfaces the real mismatch.
 */
internal fun KotlinResolver.coerceLambdaReturnVarToUnit(
    sym: KotlinSymbol,
    call: KtCallExpression,
    expected: KotlinType?,
    bindings: MutableMap<String, TypeRef>
) {
    if (expected == null || expected.nullable || expected.qualifiedName != "kotlin.Unit") return
    val ret = sym.type as? KotlinType ?: return
    if (!ret.isTypeParameter || ret.qualifiedName !in sym.typeParameters) return
    if (!bindsFunctionalReturnFromLambda(sym, call, ret.qualifiedName)) return
    bindings[ret.qualifiedName] = service.typeByFqn("kotlin.Unit")
}

/** Whether [call] passes a LAMBDA to a parameter whose functional return type is the type parameter [param],
 *  i.e. [param] is bound from that lambda's result (`coroutineScope { … }`'s `block: … -> R`). */
private fun KotlinResolver.bindsFunctionalReturnFromLambda(
    sym: KotlinSymbol,
    call: KtCallExpression,
    param: String
): Boolean {
    call.valueArguments.forEachIndexed { i, arg ->
        if (arg.getArgumentExpression() !is KtLambdaExpression) return@forEachIndexed
        val pt = (sym.paramTypes.getOrNull(lambdaParamIndex(call, i, sym))
            ?: sym.paramTypes.lastOrNull()) as? KotlinType ?: return@forEachIndexed
        val ret = service.functionalShape(pt)?.returnType as? KotlinType ?: return@forEachIndexed
        if (ret.isTypeParameter && ret.qualifiedName == param) return true
    }
    return false
}

/** Each of a function's own type parameters mapped to its erased upper bound, so any that argument
 *  inference leaves unbound still resolves to a concrete type rather than a member-less `T`. */
internal fun KotlinResolver.methodTypeParamErasure(sym: KotlinSymbol): Map<String, TypeRef> {
    if (sym.typeParameters.isEmpty() || sym.typeParameterBounds.isEmpty()) return emptyMap()
    val out = HashMap<String, TypeRef>(sym.typeParameters.size)
    sym.typeParameters.forEachIndexed { i, name ->
        sym.typeParameterBounds.getOrNull(i)?.let { out[name] = it }
    }
    return out
}

internal fun KotlinResolver.inferTypeArguments(
    sym: KotlinSymbol,
    call: KtCallExpression
): Map<String, TypeRef> {
    if (sym.typeParameters.isEmpty()) return emptyMap()
    val bindings = HashMap<String, TypeRef>()
    // Explicit type arguments (`mutableStateOf<List<Int>?>(null)`, `emptyList<String>()`) pin the callee's
    // type parameters verbatim — Kotlin does NO argument inference when they're spelled out, so a non-binding
    // value arg (`null`) can't leave `T` free. Take them positionally, mirroring [constructorResultType].
    call.typeArgumentList?.arguments?.takeIf { it.isNotEmpty() }?.let { targs ->
        sym.typeParameters.forEachIndexed { i, name ->
            targs.getOrNull(i)?.typeReference?.text
                ?.let { service.typeFromText(it, fileContext, enclosingClassFqnOf(call)) }?.let { bindings[name] = it }
        }
        return bindings
    }
    // valueArguments already includes a trailing lambda (its getArgumentExpression() is the lambda). Map each
    // argument to the DECLARED parameter it fills — via [lambdaParamIndex], which honors named arguments and
    // Kotlin's trailing-lambda-fills-the-last-parameter rule — not its positional slot: `async { 42 }` passes
    // only the trailing `block` lambda but `block` is the LAST param (after the defaulted `context`/`start`),
    // so a positional index would bind the lambda against `context` and never pin `T` from `{ 42 }` (leaving a
    // raw `Deferred` whose `.await()` loses `Int`). The `lastOrNull()` fallback stays for a vararg tail.
    call.valueArguments.forEachIndexed { i, arg ->
        val expr = arg.getArgumentExpression() ?: return@forEachIndexed
        val paramIndex = lambdaParamIndex(call, i, sym)
        val pt =
            (sym.paramTypes.getOrNull(paramIndex) ?: sym.paramTypes.lastOrNull()) as? KotlinType
                ?: return@forEachIndexed
        when {
            // A lambda handed to a parameter typed by the bare variable (`id { x -> … }`) makes the variable a
            // function type. Leaving it at its erased bound typed `id { … }` as an `Any`, which then failed every
            // function-typed parameter it was passed on to.
            expr is KtLambdaExpression && pt.typeArguments.isEmpty() && pt.qualifiedName in sym.typeParameters ->
                bindings.getOrPut(pt.qualifiedName) { lambdaFunctionType(expr) }
            expr is KtLambdaExpression -> bindLambdaReturn(pt, expr, bindings)
            expr is KtCallableReferenceExpression && bindCallableReferenceReturn(pt, expr, bindings) -> {}
            // A spread `*arr` fills the vararg with the array's ELEMENTS: `listOf(*arr)` binds T to the element.
            // An argument still carrying ANOTHER call's unfixed type variable (`getOrDefault(emptyList())`, whose `T`
            // nothing has fixed) does not override the lower bound the variable already has (`R ≥ T` from the
            // receiver): binding from it leaked that variable into the result.
            else -> inferType(expr)?.let { if (arg.getSpreadElement() != null) spreadElementType(it) else it }
                ?.takeIf { !(pt.isTypeParameter && pt.qualifiedName in sym.typeParamLowerBounds && mentionsForeignTypeParameter(it, expr)) }
                ?.let { unify(pt, throughTypeParameterBound(pt, it, expr), bindings) }
        }
    }
    return bindings
}

/** Whether [t] names a type parameter that is not in scope at [at]: a variable of some callee left unfixed. */
internal fun KotlinResolver.mentionsForeignTypeParameter(t: KotlinType, at: KtExpression, depth: Int = 0): Boolean {
    if (depth > 6) return false
    if (t.isTypeParameter && '.' !in t.qualifiedName && !isTypeParameterInScope(t.qualifiedName, at.textRange.startOffset)) return true
    return t.typeArguments.any { (it as? KotlinType)?.let { a -> mentionsForeignTypeParameter(a, at, depth + 1) } == true }
}

/**
 * The argument type [at] as the generic parameter [pt] sees it: a value typed by an in-scope type parameter
 * (`x: X` with `<X : Inv<out String>>`) is an `Inv<...>` only through its bound, and K2's constraint
 * `X <: Inv<Y>` is solved through that supertype, so `bar(x)` for `fun <Y> bar(l: Inv<Y>): Y` binds `Y` from
 * the bound's argument. Anything else is returned as it is.
 */
internal fun KotlinResolver.throughTypeParameterBound(pt: KotlinType, at: KotlinType, expr: KtExpression): KotlinType {
    if (pt.typeArguments.isEmpty() || at.typeArguments.isNotEmpty() || '.' in at.qualifiedName ||
        at.qualifiedName == pt.qualifiedName || pt.isTypeParameter
    ) return at
    return receiverForMembers(at, expr.textRange.startOffset) ?: at
}

/**
 * The function type a lambda with no expected functional type stands for: `kotlin.FunctionN` over its written
 * parameter types, returning what its body returns when it takes no parameters, else `Any?`. A parameter with
 * no written type stays UNKNOWN (a classifier no index knows, which every check backs off from) rather than
 * any real type: the function type can flow on to type another lambda's parameters (`select(id { x, y -> … },
 * { x, y -> x.inv() })`), and a guessed type there would make their members unresolved. Typing the body of a
 * lambda whose parameters nothing types would only guess too, and nested `id { x -> … }` lambdas made that
 * guess exponential.
 */
internal fun KotlinResolver.lambdaFunctionType(lambda: KtLambdaExpression): KotlinType {
    val params = lambda.valueParameters.map { p ->
        p.typeReference?.text?.let { service.typeFromText(it, fileContext, enclosingClassFqnOf(lambda)) } ?: KotlinType(UNKNOWN_LAMBDA_PARAMETER)
    }
    val result = (if (params.isEmpty()) inferLambdaResult(lambda) as? KotlinType else null)
        ?: service.typeByFqn("kotlin.Any").withNullable(true)
    return service.typeByFqn("kotlin.Function${params.size}", params + result)
}

private const val UNKNOWN_LAMBDA_PARAMETER = "<unknown>"

/**
 * The own type parameters of [call]'s callee that cannot be inferred at this site — Kotlin's
 * "Not enough information to infer type variable T" (`mutableStateOf()` with no argument: `T` is observed
 * by the result `MutableState<T>` but nothing supplies it). A type parameter qualifies only when:
 *  - the call carries NO explicit type-argument list (`mutableStateOf<String>()` supplies it),
 *  - it appears in the callee's RETURN type (so the result observes it),
 *  - no value argument binds it (argument inference left it free),
 *  - it is not the extension receiver's own type parameter (a `T.foo()` binds `T` from the receiver), and
 *  - it constrains some value parameter, EVERY one of which received no argument — so no argument could
 *    have bound it. This distinguishes the omitted-argument case from `mutableStateOf(x)` where an
 *    argument is present and inference merely (best-effort) failed.
 * A type parameter no value parameter mentions at all (`fun <T> materialize(): T`) qualifies instead when the
 * call sits where nothing outside it can constrain it either (see [noOuterConstraintAt]).
 * Backs off entirely when a concrete expected type drives the inference (`val s: MutableState<String> =
 * mutableStateOf()`), a spread argument is present, or the callee can't be resolved — conservative, so it
 * never reports a type Kotlin would accept. Shared by the editor diagnostic and the interpreter lowering.
 */
fun KotlinResolver.uninferableTypeParameters(call: KtCallExpression): List<String> {
    if (call.typeArgumentList != null) return emptyList()
    if (call.valueArguments.any { it.getSpreadElement() != null }) return emptyList()
    val sym = resolveCalleeFunction(call) ?: return emptyList()
    if (sym.typeParameters.isEmpty()) return emptyList()
    val ret = sym.type as? KotlinType
    // A concrete expected type at this position can drive the inference, so it isn't uninferable -- except for
    // a variable the written result type does not mention, which no expected type reaches.
    var expectedDrives = false
    expectedTypeAt(call.textRange.startOffset)?.let { exp ->
        if (!exp.isTypeParameter && service.isKnownType(exp.qualifiedName)) expectedDrives = true
    }
    // Likewise the RESULT of a lambda whose expected return type is concrete: `transitionSpec = { spring() }`
    // fills `Segment<S>.() -> FiniteAnimationSpec<Dp>`, which pins `spring`'s T to Dp.
    lambdaResultSlotType(call)?.let { exp ->
        if (!exp.isTypeParameter && service.isKnownType(exp.qualifiedName)) expectedDrives = true
    }
    if (expectedDrives) {
        val fn = (sym.declaration() as? KotlinDomNode)?.psi as? KtNamedFunction
        if (!sym.origin.fromSource || fn?.typeReference == null) return emptyList()
        val silent = sym.typeParameters.filter { !mentionsAnyName(fn.typeReference, setOf(it)) }
        if (silent.isEmpty()) return emptyList()
        return uninferableTypeParameters(call, sym, ret).filter { it in silent }
    }
    return uninferableTypeParameters(call, sym, ret)
}

private fun KotlinResolver.uninferableTypeParameters(call: KtCallExpression, sym: KotlinSymbol, ret: KotlinType?): List<String> {
    val bound = inferTypeArguments(sym, call).keys
    val supplied = suppliedValueParameterIndices(sym, call)
    // Variables nothing constrains across nested calls and in the positions the compiler completes on their own
    // ([unconstrainedOwnVariables], [freeOutside]): `id(materialize())`, `foo().bar()`, `run { make() }`.
    val unconstrained by lazy {
        if (plainGenericCallee(call, sym)) unconstrainedOwnVariables(sym, call) else emptySet()
    }
    return sym.typeParameters.filter { tp ->
        if (tp == sym.receiverTypeParam) return@filter false
        if (tp !in bound && classicallyUninferable(call, sym, tp, ret, supplied)) return@filter true
        tp in unconstrained && freeOutside(call, sym, tp)
    }
}

private fun KotlinResolver.classicallyUninferable(
    call: KtCallExpression,
    sym: KotlinSymbol,
    tp: String,
    ret: KotlinType?,
    supplied: Set<Int>,
): Boolean {
    val mentioning = sym.paramTypes.indices.filter { mentionsTypeParam(sym.paramTypes[it], tp) }
    if (mentioning.isEmpty()) {
        // No value parameter can ever pin it (`fun <T> materialize(): T`, `emptyList()`), so it is
        // uninferable exactly when nothing outside the call constrains it either: the receiver does not
        // mention it and the call sits where Kotlin gives it no expected type.
        if (sym.receiverTypeArgs.any { mentionsTypeParam(it, tp) }) return false
        return noOuterConstraintAt(call, sym, tp)
    }
    if (ret == null || !mentionsTypeParam(ret, tp)) return false
    return mentioning.none { it in supplied }
}

/**
 * Whether the expression [e] (a call, or the qualified expression it ends) sits where its value is discarded
 * or typed by nothing: a statement that is not a lambda's or a branch's result, or the initializer or
 * expression body of a declaration with no written type. Never inside a lambda or an anonymous function, where
 * the call may belong to a builder inference (`buildList { val x = make(); add(x) }`), which infers a local's
 * type from how it is used later in the lambda.
 */
internal fun discardedOrUntypedPosition(e: KtExpression): Boolean {
    val positionFree = when (val parent = e.parent) {
        is KtBlockExpression -> {
            val last = parent.statements.lastOrNull() === e
            !last || when (parent.parent) {
                is KtNamedFunction, is KtPropertyAccessor, is KtClassInitializer, is KtSecondaryConstructor -> true
                else -> false
            }
        }
        is KtProperty -> parent.initializer === e && parent.typeReference == null && !parent.hasDelegate()
        // Not an anonymous function (`run(fun() = make())`): its result type comes from what is expected of it.
        is KtNamedFunction -> parent.nameIdentifier != null && parent.bodyExpression === e && !parent.hasBlockBody() &&
            parent.typeReference == null && parent.modifierList?.hasModifier(KtTokens.OVERRIDE_KEYWORD) != true
        else -> false
    }
    if (!positionFree) return false
    var up: KtElement? = e.parent
    while (up != null && !(up is KtNamedFunction && up.nameIdentifier != null) && up !is KtClassOrObject) {
        if (up is KtLambdaExpression || up is KtNamedFunction) return false
        up = up.parent
    }
    return true
}

/**
 * Whether [call] to [sym] sits where nothing outside it can constrain the callee's type parameter [tp], which
 * no argument can pin either. That is a statement whose value is discarded (any block statement but a
 * lambda's or a branch's last), and the initializer or expression body of a declaration with no written type.
 * Anything else (an argument, a return, an operand, a lambda result, an if/when branch) may carry an expected
 * type our model does not see, so it does not qualify.
 *
 * The rest is read off the declaration where there is one, since a symbol reaches here with its receiver's
 * type arguments already substituted: the callee must be the call's only candidate, neither its receiver nor
 * any value parameter may mention [tp], and no type parameter bound may name another type parameter
 * (`R : List<T>` lets inference reach one parameter through another). A library callee qualifies only as a
 * plain top-level or static function, never an extension or a member reached through a receiver.
 */
private fun KotlinResolver.noOuterConstraintAt(call: KtCallExpression, sym: KotlinSymbol, tp: String): Boolean {
    val q = (call.parent as? KtQualifiedExpression)?.takeIf { it.selectorExpression === call }
    val e: KtExpression = q ?: call
    // A variable the written result type does not mention is beyond any expected type (`fun <T> f(): Boolean`).
    val resultSilent = ((sym.declaration() as? KotlinDomNode)?.psi as? KtNamedFunction)?.let { fn ->
        sym.origin.fromSource && fn.typeReference != null && !mentionsAnyName(fn.typeReference, setOf(tp))
    } == true
    if (!resultSilent && !discardedOrUntypedPosition(e)) return false
    // The same declaration can be listed twice (from the file and from the project index).
    val targets = runCatching { callTargets(call) }.getOrDefault(emptyList())
        .filter { it.kind == SymbolKind.METHOD || it.kind == SymbolKind.CONSTRUCTOR }
        .distinctBy { t ->
            val at = ((t.declaration() as? KotlinDomNode)?.psi)?.textRange?.startOffset
            if (t.origin.fromSource && at != null) Pair(t.origin.file?.toString(), at) else t
        }
    if (targets.size != 1 || targets[0].typeParameters != sym.typeParameters) return false
    if (sym.typeParamBoundNames.any { it != null }) return false
    if (!sym.origin.fromSource) {
        if (sym.kind != SymbolKind.METHOD || sym.isExtension || sym.receiverTypeFqn != null) return false
        if (q != null && !isTypeReceiver(q.receiverExpression)) return false
        if (sym.paramTypes.any { it == null }) return false
        // An erased binary bound may hide a path from one type parameter to another: nothing may pin any.
        val supplied = suppliedValueParameterIndices(sym, call)
        return supplied.none { i -> sym.typeParameters.any { mentionsTypeParam(sym.paramTypes.getOrNull(i), it) } }
    }
    val fn = (sym.declaration() as? KotlinDomNode)?.psi as? KtNamedFunction ?: return false
    if (fn.typeConstraints.isNotEmpty()) return false
    val own = setOf(tp)
    if (mentionsAnyName(fn.receiverTypeReference, own)) return false
    if (fn.valueParameters.any { it.typeReference == null || mentionsAnyName(it.typeReference, own) }) return false
    // Every type parameter the bounds could name: the function's own and its enclosing classes'.
    val typeParamNames = HashSet<String>()
    fn.typeParameters.mapNotNullTo(typeParamNames) { it.name }
    var owner: KtElement? = fn.parent
    while (owner != null) {
        if (owner is KtClass) owner.typeParameters.mapNotNullTo(typeParamNames) { it.name }
        owner = owner.parent
    }
    return fn.typeParameters.none { p -> mentionsAnyName(p.extendsBound, typeParamNames) }
}

/** Whether the type text under [t] names any of [names] (a written bound such as `List<T>`). */
private fun mentionsAnyName(t: KtElement?, names: Set<String>): Boolean {
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

/** The return type a lambda expects when [call] (or the qualified expression it ends) is that lambda's LAST
 *  statement, i.e. its result. Null anywhere else, or when the lambda's expected shape is unknown. */
private fun KotlinResolver.lambdaResultSlotType(call: KtCallExpression): KotlinType? {
    val e: KtExpression = (call.parent as? KtQualifiedExpression)?.takeIf { it.selectorExpression === call } ?: call
    val block = e.parent as? KtBlockExpression ?: return null
    if (block.statements.lastOrNull() !== e) return null
    val lambda = (block.parent as? KtFunctionLiteral)?.parent as? KtLambdaExpression ?: return null
    return expectedLambdaShape(lambda)?.returnType as? KotlinType
}

/** Whether the type reference [t] is, or nests, the type parameter named [name]. */
internal fun KotlinResolver.mentionsTypeParam(t: TypeRef?, name: String): Boolean {
    if (t == null) return false
    if (t is KotlinType && t.isTypeParameter && t.qualifiedName == name) return true
    return t.typeArguments.any { mentionsTypeParam(it, name) }
}

/** The declared value-parameter indices that received an argument at [call]: a named argument by its
 *  parameter name, a trailing lambda by Kotlin's trailing-lambda rule (the last parameter), a positional
 *  argument past a vararg by the vararg, the rest by position. A named argument whose name matches no parameter contributes nothing. */
internal fun KotlinResolver.suppliedValueParameterIndices(
    sym: KotlinSymbol,
    call: KtCallExpression
): Set<Int> {
    val paramCount = maxOf(sym.paramTypes.size, sym.paramNames.size)
    val out = HashSet<Int>()
    call.valueArguments.forEachIndexed { i, arg ->
        val named = arg.getArgumentName()?.asName?.identifier
        val idx = when {
            named != null -> sym.paramNames.indexOf(named).takeIf { it >= 0 }
                ?: return@forEachIndexed

            arg is KtLambdaArgument -> (paramCount - 1).coerceAtLeast(0)
            sym.varargParamIndex in 0..<i -> sym.varargParamIndex
            else -> i
        }
        out += idx
    }
    return out
}

/**
 * The name (or `#index`) of a value parameter the [call] leaves unfilled even though it is REQUIRED — no
 * default, not a vararg — i.e. Kotlin's "No value passed for parameter 'x'" (`Button { }` omits the
 * required `onClick`). Null when the call is valid OR can't be judged soundly. Conservative over the
 * parse-only model, mirroring the other call checks:
 *  - backs off entirely if ANY candidate overload's per-parameter defaults are UNKNOWN (Java bytecode, an
 *    old cache) — that overload might accept the call;
 *  - backs off on a spread argument (arity opaque);
 *  - reports only when EVERY candidate is missing a required parameter (so a sibling overload that accepts
 *    the args makes it valid — `remember { }` vs `remember(vararg, calc)`).
 * Used by both the editor diagnostic (`kt.argumentCount`) and the Compose preview lowering (which rejects
 * the call rather than running invalid code with a null stand-in for the missing argument).
 */
fun KotlinResolver.missingRequiredArgument(call: KtCallExpression): String? {
    if (call.valueArguments.any { it.getSpreadElement() != null }) return null
    val candidates = runCatching { callTargets(call) }.getOrDefault(emptyList())
        .filter { it.kind == SymbolKind.METHOD || it.kind == SymbolKind.CONSTRUCTOR }
    if (candidates.isEmpty()) return null
    // Every candidate must carry KNOWN per-parameter defaults (size matches its params); else an overload we
    // can't judge might accept the call, so we must not flag.
    if (candidates.any { it.paramTypes.isNotEmpty() && it.paramHasDefault.size != it.paramTypes.size }) return null
    // A first pass on the candidates' own defaults: the common outcome, some overload satisfied, costs nothing more.
    for (c in candidates) {
        val supplied = suppliedValueParameterIndices(c, call)
        if (c.paramTypes.indices.none { i -> i !in supplied && i != c.varargParamIndex && c.paramHasDefault.getOrElse(i) { true } == false }) return null
    }
    // An override takes its parameters' defaults from what it overrides, from EVERY overridden function: with
    // `Foo.some(x = 1, y)` and `Bar.some(x, y = 2)` both implemented by `Impl.some(x, y)`, `Impl().some()` is
    // complete (KT-40327). Candidates of one signature are one override family, so their defaults combine.
    fun signature(c: KotlinSymbol) = c.paramTypes.joinToString(",") { (it as? KotlinType)?.qualifiedName?.substringAfterLast('.') ?: "?" }
    val familyDefaults = HashMap<String, BooleanArray>()
    for (c in candidates) {
        if (c.kind != SymbolKind.METHOD) continue
        val acc = familyDefaults.getOrPut(signature(c)) { BooleanArray(c.paramTypes.size) }
        if (acc.size != c.paramTypes.size) continue
        c.paramHasDefault.forEachIndexed { i, d -> if (d) acc[i] = true }
    }
    var report: String? = null
    for (c in candidates) {
        val supplied = suppliedValueParameterIndices(c, call)
        val inherited = if (c.kind == SymbolKind.METHOD) familyDefaults[signature(c)]?.takeIf { it.size == c.paramTypes.size } else null
        // Every `vararg` parameter may be left empty, a second one too (`fun f(vararg x: T, vararg y: T)`, an error
        // of its own that can be suppressed): the symbol records only one vararg index, the source says which.
        val sourceParams = if (c.origin.fromSource) ((c.declaration() as? KotlinDomNode)?.psi as? KtNamedFunction)?.valueParameters else null
        val missing = c.paramTypes.indices.firstOrNull { i ->
            i !in supplied && i != c.varargParamIndex && c.paramHasDefault.getOrElse(i) { true } == false &&
                inherited?.get(i) != true && sourceParams?.getOrNull(i)?.isVarArg != true
        } ?: return null // this overload is fully satisfied → the call is valid
        if (report == null) {
            val nm = c.paramNames.getOrNull(missing)
            report = if (nm != null && nm.isNotEmpty()) "'$nm'" else "#${missing + 1}"
        }
    }
    return report
}

internal fun KotlinResolver.bindingsFromValueArgs(
    sym: KotlinSymbol,
    call: KtCallExpression
): Map<String, TypeRef> =
    bindingsFromArgExprs(
        sym, call.valueArguments.map { it.getArgumentExpression() },
        spread = call.valueArguments.indices.filterTo(HashSet()) { call.valueArguments[it].getSpreadElement() != null },
    )

/** [bindingsFromValueArgs] over bare argument EXPRESSIONS, for a call site that is not a [KtCallExpression] —
 *  an operator convention (`a[i]`, `a(i)`), whose operands fill the operator's value parameters just the same. */
internal fun KotlinResolver.bindingsFromArgExprs(
    sym: KotlinSymbol,
    args: List<KtExpression?>,
    /** Indices of SPREAD arguments (`*arr`), which bind a vararg by their ELEMENT type. */
    spread: Set<Int> = emptySet(),
): Map<String, TypeRef> {
    if (sym.typeParameters.isEmpty()) return emptyMap()
    val bindings = HashMap<String, TypeRef>()
    val needed = sym.typeParameters.toHashSet()
    args.forEachIndexed { i, expr ->
        // `unify` keeps the FIRST binding, so once every type parameter is bound the remaining
        // arguments can only no-op — stop inferring siblings (a nested Compose tree fans this out hard).
        // Behaviour-identical: a key-form mismatch just means no early exit, never a different result.
        if (bindings.keys.containsAll(needed)) return bindings
        if (expr == null || expr is KtLambdaExpression) return@forEachIndexed
        // A positional argument past a vararg still fills the vararg, not the parameter after it.
        val paramIndex = if (sym.varargParamIndex in 0..<i) sym.varargParamIndex else i
        val pt = (sym.paramTypes.getOrNull(paramIndex) ?: sym.paramTypes.lastOrNull()) as? KotlinType
            ?: return@forEachIndexed
        inferType(expr)?.let { if (i in spread) spreadElementType(it) else it }
            ?.let { unify(pt, throughTypeParameterBound(pt, it, expr), bindings) }
    }
    return bindings
}

/**
 * An operator's result type with its OWN type parameters substituted, inferred from the operand expressions.
 *
 * An operator convention (`ctx[key]`, `greeter(name)`) looks its member up by name instead of going through the
 * call path, so nothing there binds the operator's type parameters — `CoroutineContext`'s
 * `operator fun <E : Element> get(key: Key<E>): E?` handed back a bare `E?`, and `coroutineContext[CoroutineName]`
 * offered no members of `CoroutineName`. Mirrors the call path: unify the operands against the parameters, then
 * fall back to each variable's erased upper bound so an operand that pins nothing still yields a usable type.
 */
internal fun KotlinResolver.operatorResultType(
    sym: KotlinSymbol,
    args: List<KtExpression?>
): KotlinType? {
    val raw = sym.type as? KotlinType ?: return null
    if (sym.typeParameters.isEmpty()) return raw
    val bindings = methodTypeParamErasure(sym) + bindingsFromArgExprs(sym, args)
    return service.substitute(raw, bindings) as? KotlinType
}

internal fun KotlinResolver.unify(
    param: KotlinType,
    arg: KotlinType,
    bindings: MutableMap<String, TypeRef>
) {
    if (param.isTypeParameter) {
        // A NULLABLE type-parameter reference (`R?`) absorbs one level of the argument's nullability:
        // `R? = Note?` ⇒ R = Note. This is the `mapNotNull`/`filterNotNull` shape — `transform: (T) -> R?`
        // with `R : Any` — so `xs.mapNotNull { … : Note? }` yields `List<Note>`, not `List<Note?>` (which would
        // make the elements spuriously nullable). A bare `R` binds the argument unchanged (`R = Note?` stays
        // nullable), so `let { it }` / `map { … }` are unaffected.
        // A projection the parameter's position itself carries is not part of the variable: `Key<in T>` against
        // `Key<in Box<V>>` makes `T = Box<V>`. Against an unprojected position (`Inv<T>` for `Inv<out X>`) the
        // variable takes the captured type, kept as the projected argument and approximated where it is read.
        val unprojected = if (param.projection.isNotEmpty() && arg.projection == param.projection) arg.withProjection("") else arg
        val bound = if (param.nullable) unprojected.withNullable(false) else unprojected
        val cur = bindings[param.qualifiedName] as? KotlinType
        when {
            cur == null -> bindings.getOrPut(param.qualifiedName) { bound }
            // The first binding wins, except that a `null` (or a `throw`) among the arguments is only a `Nothing`:
            // `arrayOf(128, null)` is an `Array<Int?>`, not an `Array<Int>`, and `listOf(null, 1)` a `List<Int?>`.
            bound.qualifiedName == "kotlin.Nothing" && cur.qualifiedName != "kotlin.Nothing" ->
                if (bound.nullable && !cur.nullable) bindings[param.qualifiedName] = cur.withNullable(true)
            cur.qualifiedName == "kotlin.Nothing" && bound.qualifiedName != "kotlin.Nothing" ->
                bindings[param.qualifiedName] = bound.withNullable(bound.nullable || cur.nullable)
        }
        return
    }
    // A vararg parameter (`of(E...)`) is an `Array<E>`; a single scalar argument unifies with the element.
    if (param.qualifiedName == "kotlin.Array" && param.typeArguments.size == 1 &&
        arg.qualifiedName != "kotlin.Array"
    ) {
        (param.typeArguments.first() as? KotlinType)?.let { unify(it, arg, bindings) }
        return
    }
    // Match the argument's type arguments against the parameter's. When the argument is a SUBTYPE of the
    // parameter's generic classifier — e.g. `StartActivityForResult` (no type args of its own) passed for a
    // `ActivityResultContract<I, O>` parameter — project it onto that classifier first, so a type parameter
    // nested in the parameter binds from the supertype's instantiation
    // (`ActivityResultContract<Intent, ActivityResult>` ⇒ O = ActivityResult). Without this the positional zip
    // against the subtype's own (fewer / differently-ordered) arguments misses O, leaving the callback lambda's
    // parameter an unbound type parameter — the `registerForActivityResult { result -> result.data }` case.
    val argArgs = if (param.qualifiedName != arg.qualifiedName && param.typeArguments.isNotEmpty())
        service.receiverSupertypeArgs(arg.qualifiedName, arg.typeArguments, param.qualifiedName)
            ?: companionSupertypeArgs(arg, param.qualifiedName)
            ?: arg.typeArguments
    else arg.typeArguments
    // A bare variable at a CONTRAVARIANT position (`In<in T>` given an `In<A>`) is only bounded above by the
    // argument (`T <: A`). Alone, that bound is what it fixes to; meeting another, the more specific one wins
    // (`foo(inA, inC)` is a `C`), and two unrelated ones (`foo(inA, inB)`) are left to the constraint system,
    // where the expected type decides. Binding the first one made `foo(inA, inC)` an `A`.
    val variances = if (param.typeArguments.any { (it as? KotlinType)?.isTypeParameter == true })
        service.classTypeParameterVariance(param.qualifiedName) else emptyList()
    param.typeArguments.zip(argArgs).forEachIndexed { i, (p, a) ->
        if (p !is KotlinType || a !is KotlinType) return@forEachIndexed
        val contravariant = p.projection == "in" || (p.projection.isEmpty() && variances.getOrNull(i) == "in")
        val cur = bindings[p.qualifiedName] as? KotlinType
        if (contravariant && p.isTypeParameter && a.projection != "*" && cur != null &&
            !cur.isTypeParameter && !a.isTypeParameter && cur.qualifiedName != a.qualifiedName
        ) {
            when {
                cur.isAssignableFrom(a) -> bindings[p.qualifiedName] = a
                a.isAssignableFrom(cur) -> {}
                else -> bindings.remove(p.qualifiedName)
            }
            return@forEachIndexed
        }
        unify(p, a, bindings)
    }
}

/**
 * A bare classifier used as a VALUE denotes its COMPANION object: `coroutineContext[CoroutineName]` passes
 * `CoroutineName.Key` (the companion, which is the `CoroutineContext.Key<CoroutineName>`), not the class. The
 * expression still types as the CLASS here — a name reference resolves to its classifier, which is what
 * qualifier resolution (`Type.NESTED`, `Type.CONST`) needs — so when the class itself does not project onto
 * [superFqn], project its companion instead. That is what binds `E` in `get(key: Key<E>): E?` to
 * `CoroutineName`, so `coroutineContext[CoroutineName]?.name` resolves. Null when the type has no companion,
 * or the companion is not a subtype of [superFqn] either.
 */
private fun KotlinResolver.companionSupertypeArgs(arg: KotlinType, superFqn: String): List<TypeRef>? =
    service.companionObjectFqn(arg.qualifiedName)
        ?.let { service.receiverSupertypeArgs(it, emptyList(), superFqn) }
