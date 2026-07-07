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
    val sym = resolveCalleeFunction(call) ?: return null
    val raw = sym.paramTypes.getOrNull(lambdaParamIndex(call, argIndex, sym)) as? KotlinType ?: return null
    val bound = service.substitute(raw, bindingsFromValueArgs(sym, call)) as? KotlinType ?: return null
    return service.functionalShape(bound)
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
