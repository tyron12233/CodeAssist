package dev.ide.lang.kotlin.resolve

import dev.ide.lang.kotlin.symbols.KotlinSymbol
import dev.ide.lang.kotlin.symbols.KotlinSymbolService
import dev.ide.lang.kotlin.symbols.KotlinType
import dev.ide.lang.resolve.SymbolKind
import dev.ide.lang.resolve.TypeRef
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtConstantExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtLambdaArgument
import org.jetbrains.kotlin.psi.KtLambdaExpression

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
        val pt = (sym.paramTypes.getOrNull(i) ?: sym.paramTypes.lastOrNull()) as? KotlinType
            ?: return@forEachIndexed
        val ret = service.functionalShape(pt)?.returnType as? KotlinType
        if (ret?.isTypeParameter == true && ret.qualifiedName == param) {
            val last = lambda.bodyExpression?.statements?.lastOrNull()
            if (last is KtConstantExpression && last.text.trim() == "null") return true
        }
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
                ?.let { service.typeFromText(it, fileContext) }?.let { bindings[name] = it }
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
        if (expr is KtLambdaExpression) bindLambdaReturn(pt, expr, bindings)
        else inferType(expr)?.let { unify(pt, it, bindings) }
    }
    return bindings
}

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
 * Backs off entirely when a concrete expected type drives the inference (`val s: MutableState<String> =
 * mutableStateOf()`), a spread argument is present, or the callee can't be resolved — conservative, so it
 * never reports a type Kotlin would accept. Shared by the editor diagnostic and the interpreter lowering.
 */
fun KotlinResolver.uninferableTypeParameters(call: KtCallExpression): List<String> {
    if (call.typeArgumentList != null) return emptyList()
    if (call.valueArguments.any { it.getSpreadElement() != null }) return emptyList()
    val sym = resolveCalleeFunction(call) ?: return emptyList()
    if (sym.typeParameters.isEmpty()) return emptyList()
    val ret = sym.type as? KotlinType ?: return emptyList()
    // A concrete expected type at this position can drive the inference, so it isn't uninferable.
    expectedTypeAt(call.textRange.startOffset)?.let { exp ->
        if (!exp.isTypeParameter && service.isKnownType(exp.qualifiedName)) return emptyList()
    }
    val bound = inferTypeArguments(sym, call).keys
    val supplied = suppliedValueParameterIndices(sym, call)
    return sym.typeParameters.filter { tp ->
        if (tp in bound || tp == sym.receiverTypeParam) return@filter false
        if (!mentionsTypeParam(ret, tp)) return@filter false
        val mentioning = sym.paramTypes.indices.filter { mentionsTypeParam(sym.paramTypes[it], tp) }
        mentioning.isNotEmpty() && mentioning.none { it in supplied }
    }
}

/** Whether the type reference [t] is, or nests, the type parameter named [name]. */
internal fun KotlinResolver.mentionsTypeParam(t: TypeRef?, name: String): Boolean {
    if (t == null) return false
    if (t is KotlinType && t.isTypeParameter && t.qualifiedName == name) return true
    return t.typeArguments.any { mentionsTypeParam(it, name) }
}

/** The declared value-parameter indices that received an argument at [call]: a named argument by its
 *  parameter name, a trailing lambda by Kotlin's trailing-lambda rule (the last parameter), the rest by
 *  position. A named argument whose name matches no parameter contributes nothing. */
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
    var report: String? = null
    for (c in candidates) {
        val supplied = suppliedValueParameterIndices(c, call)
        val missing = c.paramTypes.indices.firstOrNull { i ->
            i !in supplied && i != c.varargParamIndex && c.paramHasDefault.getOrElse(i) { true } == false
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
): Map<String, TypeRef> {
    if (sym.typeParameters.isEmpty()) return emptyMap()
    val bindings = HashMap<String, TypeRef>()
    val needed = sym.typeParameters.toHashSet()
    call.valueArguments.forEachIndexed { i, arg ->
        // `unify` is putIfAbsent (first binding wins), so once every type parameter is bound the remaining
        // arguments can only no-op — stop inferring siblings (a nested Compose tree fans this out hard).
        // Behaviour-identical: a key-form mismatch just means no early exit, never a different result.
        if (bindings.keys.containsAll(needed)) return bindings
        val expr = arg.getArgumentExpression() ?: return@forEachIndexed
        if (expr is KtLambdaExpression) return@forEachIndexed
        val pt = (sym.paramTypes.getOrNull(i) ?: sym.paramTypes.lastOrNull()) as? KotlinType
            ?: return@forEachIndexed
        inferType(expr)?.let { unify(pt, it, bindings) }
    }
    return bindings
}

internal fun KotlinResolver.unify(
    param: KotlinType,
    arg: KotlinType,
    bindings: MutableMap<String, TypeRef>
) {
    if (param.isTypeParameter) {
        bindings.putIfAbsent(param.qualifiedName, arg); return
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
        service.receiverSupertypeArgs(arg.qualifiedName, arg.typeArguments, param.qualifiedName) ?: arg.typeArguments
    else arg.typeArguments
    param.typeArguments.zip(argArgs).forEach { (p, a) ->
        if (p is KotlinType && a is KotlinType) unify(p, a, bindings)
    }
}
