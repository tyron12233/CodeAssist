package dev.ide.lang.kotlin.resolve

import dev.ide.lang.kotlin.symbols.KotlinSymbol
import dev.ide.lang.kotlin.symbols.KotlinSymbolService
import dev.ide.lang.kotlin.symbols.KotlinType
import dev.ide.lang.kotlin.symbols.TypeRendering
import dev.ide.lang.resolve.TypeRef
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtLambdaArgument
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtValueArgument
import org.jetbrains.kotlin.psi.KtValueArgumentList
import org.jetbrains.kotlin.psi.ValueArgument

/** Lambda inference: expected functional type, receiver and parameter types, and the enclosing-call parameter slot. */

/** A lambda fills a functional parameter (a Kotlin `(…) -> R` or a Java SAM); bind its result type
 *  parameter from the lambda's inferred result (`map { … }`'s `R`, `let`'s `R`). */
internal fun KotlinResolver.bindLambdaReturn(pt: KotlinType, lambda: KtLambdaExpression, bindings: MutableMap<String, TypeRef>) {
    val r = service.functionalShape(pt)?.returnType as? KotlinType ?: return
    if (r.isTypeParameter) inferLambdaResult(lambda)?.let { bindings.putIfAbsent(r.qualifiedName, it) }
}

internal fun KotlinResolver.inferLambdaResult(lambda: KtLambdaExpression): TypeRef? =
    inferType(lambda.bodyExpression?.statements?.lastOrNull() as? KtExpression)

/** The `(P…) -> R` type a lambda is expected to be (from the parameter it fills), receiver-bound — used
 *  to type the lambda's `it`/named parameters. */
/** When [lambda] fills an EXTENSION-function-typed parameter (`RowScope.() -> Unit`), the receiver type its
 *  body has as an implicit `this` (the Compose-scope content-lambda case); null for a plain lambda. */
fun KotlinResolver.lambdaReceiverType(lambda: KtLambdaExpression): KotlinType? =
    expectedFunctionTypeFor(lambda)?.takeIf { it.isExtensionFunctionType }?.typeArguments?.firstOrNull() as? KotlinType

internal fun KotlinResolver.expectedFunctionTypeFor(lambda: KtLambdaExpression): KotlinType? {
    val (call, argIndex) = enclosingCallAndParamIndex(lambda) ?: return null
    val sym = resolveCalleeFunction(call) ?: return null
    val raw = sym.paramTypes.getOrNull(lambdaParamIndex(call, argIndex, sym)) as? KotlinType ?: return null
    if (!TypeRendering.isFunctionType(raw.qualifiedName)) return null
    // Bind the function's type params from the NON-lambda value args (with(x){…} binds T from x), so the
    // block's receiver/params are concrete. (Skip lambdas to avoid recursing back into this lambda.)
    return service.substitute(raw, bindingsFromValueArgs(sym, call)) as? KotlinType
}

/** The value-parameter types a lambda receives (in order), from the functional parameter it fills — for
 *  inlay hints on `{ x -> … }` / the implicit `it`. Handles both a Kotlin function-type parameter and a
 *  Java SAM (`stream().map { it }`). Empty when the expected type is unknown. */
fun KotlinResolver.lambdaParameterTypes(lambda: KtLambdaExpression): List<TypeRef?> =
    expectedLambdaShape(lambda)?.parameterTypes ?: emptyList()

/** The functional shape (value-param types + result) a lambda is expected to satisfy — its parameter slot
 *  in the enclosing call, resolved to a Kotlin function type or a Java SAM, with the call's non-lambda
 *  arguments already used to bind the function's type parameters. */
internal fun KotlinResolver.expectedLambdaShape(lambda: KtLambdaExpression): KotlinSymbolService.FunctionalShape? {
    val (call, argIndex) = enclosingCallAndParamIndex(lambda) ?: return null
    // A SAM constructor (`Comparator<String> { a, b -> … }`, a project `fun interface`) has no callee FUNCTION —
    // the lambda IS the interface's single-abstract-method body. Fall back to that method's shape.
    val sym = resolveCalleeFunction(call) ?: return samConstructorShape(call)
    val raw = sym.paramTypes.getOrNull(lambdaParamIndex(call, argIndex, sym)) as? KotlinType ?: return samConstructorShape(call)
    // Bind the function's type params from the NON-lambda value args (`with(x){…}` binds T from x) AND the call's
    // EXPLICIT type arguments (`Comparator<String> { … }` → T = String — a SAM constructor's only value argument
    // is the lambda, so value-arg inference alone leaves T unbound), so the block's receiver/params are concrete.
    val bindings = bindingsFromValueArgs(sym, call) + bindingsFromTypeArgs(sym, call)
    val bound = service.substitute(raw, bindings) as? KotlinType ?: return null
    return service.functionalShape(bound)
}

/** Bind a callee's type parameters from the call's EXPLICIT type arguments (`Comparator<String> { … }` →
 *  `T = String`). Complements [bindingsFromValueArgs] for a call whose only value argument is the lambda itself,
 *  where value-argument inference has nothing to bind from. */
internal fun KotlinResolver.bindingsFromTypeArgs(sym: KotlinSymbol, call: KtCallExpression): Map<String, TypeRef> {
    if (sym.typeParameters.isEmpty()) return emptyMap()
    val args = call.typeArgumentList?.arguments ?: return emptyMap()
    val bindings = HashMap<String, TypeRef>()
    sym.typeParameters.forEachIndexed { i, tp ->
        args.getOrNull(i)?.typeReference?.text?.let { service.typeFromText(it, fileContext) }?.let { bindings[tp] = it }
    }
    return bindings
}

/** A SAM-constructor call `Interface<Args> { … }` (`Comparator<String> { a, b -> … }`, or a project
 *  `fun interface`): the callee names a functional interface and the lambda IS its single-abstract-method
 *  body, so the lambda's parameter/result types are that method's, with the interface's type parameters bound
 *  from the call's explicit type arguments. Null when the callee doesn't name a (single-abstract-method)
 *  interface — then it's an ordinary call, not a SAM constructor. */
internal fun KotlinResolver.samConstructorShape(call: KtCallExpression): KotlinSymbolService.FunctionalShape? {
    val callee = call.calleeExpression as? KtNameReferenceExpression ?: return null
    val fqn = service.resolveTypeName(callee.getReferencedName(), fileContext) ?: return null
    if (service.isInterfaceType(fqn) != true) return null
    val typeArgs = call.typeArgumentList?.arguments.orEmpty()
        .mapNotNull { it.typeReference?.text?.let { t -> service.typeFromText(t, fileContext) } }
    return service.functionalShape(service.typeByFqn(fqn, typeArgs))
}

/** The call a lambda argument belongs to + its parameter index. `KtCallExpression.valueArguments`
 *  ALREADY includes the trailing lambda, so the index is its position in that list. */
internal fun KotlinResolver.enclosingCallAndParamIndex(lambda: KtLambdaExpression): Pair<KtCallExpression, Int>? {
    val valueArg = lambda.parent as? ValueArgument ?: return null
    val call = when (val p = lambda.parent) {
        is KtLambdaArgument -> p.parent as? KtCallExpression
        is KtValueArgument -> (p.parent as? KtValueArgumentList)?.parent as? KtCallExpression
        else -> null
    } ?: return null
    return call to call.valueArguments.indexOf(valueArg).coerceAtLeast(0)
}

/** The declared parameter index a lambda value-argument at [argIndex] fills: a named lambda by its name, a
 *  trailing lambda by Kotlin's trailing-lambda rule (the LAST parameter — so a defaulted leading param like
 *  `modifier` doesn't misalign `Column { }`/`LazyColumn { }`'s content receiver), else its positional index. */
internal fun KotlinResolver.lambdaParamIndex(call: KtCallExpression, argIndex: Int, sym: KotlinSymbol): Int {
    val arg = call.valueArguments.getOrNull(argIndex)
    arg?.getArgumentName()?.asName?.identifier?.let { n -> sym.paramNames.indexOf(n).takeIf { it >= 0 }?.let { return it } }
    val paramCount = maxOf(sym.paramTypes.size, sym.paramNames.size)
    if (arg is KtLambdaArgument) return (paramCount - 1).coerceAtLeast(0)
    return argIndex
}
