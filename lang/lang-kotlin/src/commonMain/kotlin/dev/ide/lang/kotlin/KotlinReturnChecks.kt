package dev.ide.lang.kotlin

import dev.ide.kotlin.syntax.psi.KtAnnotatedExpression
import dev.ide.kotlin.syntax.psi.KtBinaryExpression
import dev.ide.kotlin.syntax.psi.KtCallExpression
import dev.ide.kotlin.syntax.psi.KtExpression
import dev.ide.kotlin.syntax.psi.KtQualifiedExpression
import dev.ide.kotlin.syntax.psi.KtTokens
import dev.ide.lang.kotlin.parse.unwrapParen
import dev.ide.kotlin.syntax.psi.KtClassOrObject
import dev.ide.kotlin.syntax.psi.KtElement
import dev.ide.kotlin.syntax.psi.KtFunctionLiteral
import dev.ide.kotlin.syntax.psi.KtLabeledExpression
import dev.ide.kotlin.syntax.psi.KtLambdaArgument
import dev.ide.kotlin.syntax.psi.KtLambdaExpression
import dev.ide.kotlin.syntax.psi.KtNameReferenceExpression
import dev.ide.kotlin.syntax.psi.KtNamedFunction
import dev.ide.kotlin.syntax.psi.KtParenthesizedExpression
import dev.ide.kotlin.syntax.psi.KtReturnExpression
import dev.ide.kotlin.syntax.psi.KtValueArgument
import dev.ide.kotlin.syntax.psi.KtValueArgumentList
import dev.ide.lang.dom.Diagnostic
import dev.ide.lang.kotlin.resolve.KotlinResolver
import dev.ide.lang.kotlin.resolve.enclosingCallAndParamIndex
import dev.ide.lang.kotlin.resolve.enclosingClassFqnOf
import dev.ide.lang.kotlin.resolve.expectedFunctionTypeFor
import dev.ide.lang.kotlin.resolve.inferType
import dev.ide.lang.kotlin.resolve.lambdaParamIndex
import dev.ide.lang.kotlin.resolve.mentionsTypeParam
import dev.ide.lang.kotlin.resolve.resolveCalleeFunction
import dev.ide.lang.kotlin.symbols.KotlinType

/**
 * RETURN_TYPE_MISMATCH for `return@label value` out of a lambda: the value must fit the return type of the function
 * type the lambda is checked against (`run<Int> f@{ return@f "" }`, `val f: (Int) -> String = l@{ return@l 2 }`).
 * Judged only where that return type is fixed before the lambda is analyzed: written in the lambda's declared
 * context, or in the callee's parameter without one of the callee's type parameters the call leaves to inference
 * (an inferred `R` takes the lambda's returns into account, so nothing returned can mismatch it).
 */
internal fun KotlinSemanticChecks.labeledReturnMismatch(ret: KtReturnExpression, resolver: KotlinResolver): Diagnostic? {
    val value = ret.returnedExpression ?: return null
    val label = ret.getTargetLabel()?.getReferencedName() ?: return null
    val lambda = labeledLambdaOf(ret, label) ?: return null
    val expected = fixedLambdaReturnType(lambda, resolver) ?: return null
    if (expected.qualifiedName == "kotlin.Unit" && !expected.nullable) {
        if (typeFromGenericCall(value, resolver)) return null
        return unitReturnMismatch(value, resolver.inferType(value))
    }
    return valueMismatch(expected, value, resolver)
}

/** The lambda a `return@label` leaves: one labeled so, or passed to a call of that name. Null when the label names
 *  a function (or nothing) instead. */
private fun labeledLambdaOf(ret: KtReturnExpression, label: String): KtLambdaExpression? {
    var node: KtElement? = ret.parent
    while (node != null && node !is KtClassOrObject) {
        if (node is KtNamedFunction) return null
        if (node is KtFunctionLiteral) {
            val lambda = node.parent as? KtLambdaExpression
            if (lambda != null) {
                var outer: KtElement = lambda
                while (outer.parent is KtParenthesizedExpression || outer.parent is KtAnnotatedExpression) outer = outer.parent as KtElement
                val labeled = outer.parent as? KtLabeledExpression
                if (labeled != null) {
                    if (labeled.getLabelName() == label) return lambda
                } else if (callNameOf(lambda) == label) return lambda
            }
        }
        node = node.parent
    }
    return null
}

private fun callNameOf(lambda: KtLambdaExpression): String? {
    var arg: KtElement = lambda
    while (arg.parent is KtParenthesizedExpression || arg.parent is KtAnnotatedExpression) arg = arg.parent as KtElement
    val call = when (val p = arg.parent) {
        is KtLambdaArgument -> p.parent as? KtCallExpression
        is KtValueArgument -> (p.parent as? KtValueArgumentList)?.parent as? KtCallExpression
        else -> null
    } ?: return null
    return (call.calleeExpression as? KtNameReferenceExpression)?.getReferencedName()
}

/** The return type [lambda] is checked against when it is fixed before the lambda is analyzed (see
 *  [labeledReturnMismatch]), or null. */
private fun KotlinSemanticChecks.fixedLambdaReturnType(lambda: KtLambdaExpression, resolver: KotlinResolver): KotlinType? {
    val ft = resolver.expectedFunctionTypeFor(lambda) ?: assignedFunctionType(lambda, resolver) ?: return null
    val shape = service.functionalShape(ft) ?: return null
    val ret = shape.returnType as? KotlinType ?: return null
    if (mentionsAnyTypeParameter(ret)) return null
    if (!service.isKnownType(ret.qualifiedName)) return null
    // Passed to a call: the callee's own parameter must fix it, or the call's written type arguments.
    resolver.enclosingCallAndParamIndex(lambda)?.let { (call, argIndex) ->
        val sym = resolver.resolveCalleeFunction(call) ?: return null
        if (call.typeArgumentList != null) {
            val written = writtenLambdaType(call, argIndex, sym, resolver) ?: return null
            return (service.functionalShape(written)?.returnType as? KotlinType)
                ?.takeIf { !mentionsAnyTypeParameter(it) && service.isKnownType(it.qualifiedName) }
        }
        val raw = sym.paramTypes.getOrNull(resolver.lambdaParamIndex(call, argIndex, sym)) as? KotlinType ?: return null
        val open = (sym.typeParameters + listOfNotNull(sym.receiverTypeParam)).toSet()
        val rawRet = service.functionalShape(service.markTypeParameters(raw, open) ?: raw)?.returnType as? KotlinType ?: return null
        if (open.any { resolver.mentionsTypeParam(rawRet, it) }) return null
    }
    return ret
}

/** The function type of the parameter the lambda at [argIndex] fills, with the call's written type arguments put
 *  in for the callee's type parameters; null unless every one of them is written. */
private fun KotlinSemanticChecks.writtenLambdaType(
    call: KtCallExpression, argIndex: Int, sym: dev.ide.lang.kotlin.symbols.KotlinSymbol, resolver: KotlinResolver,
): KotlinType? {
    val written = call.typeArgumentList?.arguments.orEmpty()
    if (sym.typeParameters.isEmpty() || written.size != sym.typeParameters.size) return null
    val index = resolver.lambdaParamIndex(call, argIndex, sym)
    // An extension whose receiver names the callee's type parameters reaches here with them already bound from the
    // receiver (`In<I>.foo` on an `In<B>` has `I = B`), which the written arguments override: read the declaration.
    val raw = if (sym.isExtension && (sym.receiverTypeParam != null || sym.receiverTypeArgs.isNotEmpty())) {
        val fn = (sym.declaration() as? dev.ide.lang.kotlin.parse.KotlinDomNode)?.psi as? KtNamedFunction ?: return null
        if (fn.containingFile !== call.containingFile) return null
        val text = fn.valueParameters.getOrNull(index)?.typeReference?.text ?: return null
        service.typeFromText(text, resolver.fileContext, enclosingClassFqnOf(fn)) ?: return null
    } else sym.paramTypes.getOrNull(index) as? KotlinType ?: return null
    val bindings = HashMap<String, KotlinType>()
    for ((k, name) in sym.typeParameters.withIndex()) {
        val text = written[k].typeReference?.text ?: return null
        bindings[name] = service.typeFromText(text, resolver.fileContext, enclosingClassFqnOf(call)) ?: return null
    }
    return substituteKeepingNullability(service.markTypeParameters(raw, sym.typeParameters.toSet()) ?: raw, bindings)
}

/** The function type of the variable [lambda] is assigned to (`f = l@{ … }` with `var f: () -> Int`). */
private fun KotlinSemanticChecks.assignedFunctionType(lambda: KtLambdaExpression, resolver: KotlinResolver): KotlinType? {
    var holder: KtElement = lambda
    while (holder.parent is KtParenthesizedExpression || holder.parent is KtAnnotatedExpression || holder.parent is KtLabeledExpression) {
        holder = holder.parent as KtElement
    }
    val assignment = holder.parent as? KtBinaryExpression ?: return null
    if (assignment.operationToken != KtTokens.EQ || assignment.right !== holder) return null
    val target = resolver.inferType(assignment.left ?: return null) ?: return null
    return target.takeIf { !it.nullable && service.functionalShape(it) != null }
}

/** Whether [value]'s type is inferred from a generic call's type parameters, which an expected type would fix
 *  differently (`processNested { proceed() }` returned where `Unit` is expected is a `processNested<Unit>`). */
private fun KotlinSemanticChecks.typeFromGenericCall(value: KtExpression, resolver: KotlinResolver): Boolean {
    val call = when (val u = unwrapParen(value)) {
        is KtCallExpression -> u
        is KtQualifiedExpression -> u.selectorExpression as? KtCallExpression
        else -> null
    } ?: return false
    if (call.typeArgumentList != null) return false
    val sym = resolver.resolveCalleeFunction(call) ?: return true
    val ret = service.markTypeParameters(sym.type as? KotlinType, sym.typeParameters.toSet()) ?: return true
    return sym.typeParameters.any { resolver.mentionsTypeParam(ret, it) }
}

private fun mentionsAnyTypeParameter(t: KotlinType): Boolean =
    t.isTypeParameter || t.typeArguments.any { (it as? KotlinType)?.let(::mentionsAnyTypeParameter) == true }

/** The type of [fn]'s expression body, which has no written type: what a non-local `return` out of it must fit. */
internal fun KotlinSemanticChecks.implicitExpressionBodyType(fn: KtNamedFunction, resolver: KotlinResolver): KotlinType? {
    val body = fn.bodyExpression ?: return null
    val t = resolver.inferType(body) ?: return null
    if (mentionsAnyTypeParameter(t) || !service.isKnownType(t.qualifiedName)) return null
    return t
}

/**
 * RETURN_TYPE_MISMATCH on the value a lambda argument ends with, where the call's written type arguments fix the
 * lambda's return type (`run<Int> { "" }`, `myRun<Foo> { Bar() }`): the lambda is analyzed against that type, so
 * its last expression must fit it (`Unit` takes anything). A call without written type arguments infers the type
 * from the lambda instead, and a project function whose parameter names no type parameter is judged by
 * [lambdaArgumentCheckers] already.
 */
internal fun KotlinSemanticChecks.lambdaAgainstWrittenTypeArguments(call: KtCallExpression, resolver: KotlinResolver): List<Diagnostic> {
    if (call.typeArgumentList == null) return emptyList()
    val out = ArrayList<Diagnostic>()
    for (arg in call.valueArguments) {
        val expr = arg.getArgumentExpression() ?: continue
        var e: KtExpression = unwrapParen(expr)
        if (e is KtLabeledExpression) e = e.baseExpression?.let { unwrapParen(it) } ?: continue
        val lambda = e as? KtLambdaExpression ?: continue
        val sym = resolver.resolveCalleeFunction(call) ?: return emptyList()
        if (sym.typeParameters.isEmpty()) return emptyList()
        val argIndex = resolver.enclosingCallAndParamIndex(lambda)?.second ?: continue
        val raw = sym.paramTypes.getOrNull(resolver.lambdaParamIndex(call, argIndex, sym)) as? KotlinType ?: continue
        val open = sym.typeParameters.toSet()
        val rawRet = service.functionalShape(service.markTypeParameters(raw, open) ?: raw)?.returnType as? KotlinType ?: continue
        // The written type arguments must be what fixes it: a return type that names none is the other check's.
        if (open.none { resolver.mentionsTypeParam(rawRet, it) }) continue
        // The written type arguments, and nothing inferred, make the lambda's type.
        val ft = writtenLambdaType(call, argIndex, sym, resolver) ?: return emptyList()
        val ret = service.functionalShape(ft)?.returnType as? KotlinType ?: continue
        if (mentionsAnyTypeParameter(ret) || !service.isKnownType(ret.qualifiedName)) continue
        val arity = ft.typeArguments.size - 1 - (if (ft.isExtensionFunctionType) 1 else 0)
        val n = lambda.valueParameters.size
        if (n != 0 && n != arity) continue
        lambdaAgainstFunctionType(ft, expr, resolver)?.let { out += it }
    }
    return out
}
