package dev.ide.lang.kotlin

import dev.ide.kotlin.syntax.psi.KtCallExpression
import dev.ide.kotlin.syntax.psi.KtClassOrObject
import dev.ide.kotlin.syntax.psi.KtElement
import dev.ide.kotlin.syntax.psi.KtLabeledExpression
import dev.ide.kotlin.syntax.psi.KtLambdaArgument
import dev.ide.kotlin.syntax.psi.KtLambdaExpression
import dev.ide.kotlin.syntax.psi.KtNameReferenceExpression
import dev.ide.kotlin.syntax.psi.KtNamedFunction
import dev.ide.kotlin.syntax.psi.KtProperty
import dev.ide.kotlin.syntax.psi.KtQualifiedExpression
import dev.ide.kotlin.syntax.psi.KtReturnExpression
import dev.ide.kotlin.syntax.psi.KtValueArgument
import dev.ide.kotlin.syntax.psi.KtValueArgumentList
import dev.ide.lang.dom.Diagnostic
import dev.ide.lang.dom.Severity
import dev.ide.lang.dom.TextRange
import dev.ide.lang.kotlin.analysis.KotlinChecker
import dev.ide.lang.kotlin.resolve.KotlinResolver
import dev.ide.lang.kotlin.resolve.callTargets
import dev.ide.lang.kotlin.resolve.discardedOrUntypedPosition
import dev.ide.lang.kotlin.resolve.inferType
import dev.ide.lang.kotlin.symbols.KotlinType
import dev.ide.lang.resolve.SymbolKind

/*
 * A lambda with a bare `return@label` and no expected return type of its own returns `Unit`, after K2's lambda
 * return-type computation (`FirAnonymousFunction.computeReturnType`, KT-63563): a `return@label value` of another
 * type in the same lambda is then a RETURN_TYPE_MISMATCH. "No expected return type" is a lambda that initializes a
 * property with no written type, or one passed where the function type's result is a bare type parameter of the
 * callee that nothing else fixes (`run { if (b) return@run; return@run 4 }`).
 */

internal fun KotlinSemanticChecks.lambdaUnitReturnCheckers(): List<KotlinChecker> = listOf(
    KotlinChecker(KtLambdaExpression::class) { psi ->
        KotlinPerf.span("sem.ported.lambdaUnitReturn") {
            if (resolveReady) reportAll(valueReturnsInUnitLambda(psi as KtLambdaExpression, resolver))
        }
    },
)

private fun KotlinSemanticChecks.valueReturnsInUnitLambda(lambda: KtLambdaExpression, resolver: KotlinResolver): List<Diagnostic> {
    var holder: KtElement = lambda
    val explicitLabel = (lambda.parent as? KtLabeledExpression)?.also { holder = it }?.getLabelName()
    val call = callOf(holder)
    // Cheap gates first: the lambda has a label, it is not the body of a call the check cannot apply to, and the
    // text it spans names a `return@` at all (scanned in place, with no copy of the text).
    val label = explicitLabel ?: (call?.calleeExpression as? KtNameReferenceExpression)?.getReferencedName() ?: return emptyList()
    if (explicitLabel == null && (call == null || call.typeArgumentList != null)) return emptyList()
    val range = lambda.textRange
    if (!resolver.ktFile.sourceText.regionContains(range.startOffset, range.endOffset, "return@$label")) return emptyList()
    val returns = returnsTargeting(lambda, label)
    if (returns.none { it.returnedExpression == null }) return emptyList()
    val valued = returns.filter { it.returnedExpression != null }
    if (valued.isEmpty()) return emptyList()
    if (!noExpectedReturnType(holder, call, resolver)) return emptyList()
    val out = ArrayList<Diagnostic>()
    for (ret in valued) {
        val v = ret.returnedExpression ?: continue
        val t = resolver.inferType(v) ?: continue
        if (t.isTypeParameter || t.nullable) continue
        if (t.qualifiedName == "kotlin.Unit" || t.qualifiedName == "kotlin.Nothing" || !service.isKnownType(t.qualifiedName)) continue
        val r = v.textRange
        out += Diagnostic(TextRange(r.startOffset, r.endOffset), Severity.ERROR,
            "Type mismatch: inferred type is ${renderType(t)} but Unit was expected (this lambda also returns with a bare 'return@$label')",
            KotlinDiagnosticCodes.TYPE_MISMATCH)
    }
    return out
}

private fun CharSequence.regionContains(start: Int, end: Int, needle: String): Boolean {
    val last = minOf(end, length) - needle.length
    var i = maxOf(start, 0)
    while (i <= last) {
        if (this[i] == needle[0] && regionMatches(i, needle, 0, needle.length)) return true
        i++
    }
    return false
}

/** The call [holder] (a lambda, maybe labeled) is an argument of: trailing or inside the parentheses. */
private fun callOf(holder: KtElement): KtCallExpression? = when (val p = holder.parent) {
    is KtLambdaArgument -> p.parent as? KtCallExpression
    is KtValueArgument -> (p.parent as? KtValueArgumentList)?.parent as? KtCallExpression
    else -> null
}

/** The `return@label` expressions aimed at [lambda]: in its body and nested lambdas, not past a nested function,
 *  class, or lambda that takes the same label. */
private fun returnsTargeting(lambda: KtLambdaExpression, label: String): List<KtReturnExpression> {
    val out = ArrayList<KtReturnExpression>()
    fun visit(el: KtElement) {
        if (el is KtNamedFunction || el is KtClassOrObject) return
        if (el is KtLabeledExpression && el.getLabelName() == label) return
        if (el is KtLambdaExpression && el.parent !is KtLabeledExpression &&
            (callOf(el)?.calleeExpression as? KtNameReferenceExpression)?.getReferencedName() == label
        ) return
        if (el is KtReturnExpression && el.getLabelName() == label) out += el
        var c = el.firstChild
        while (c != null) { visit(c); c = c.nextSibling }
    }
    lambda.bodyExpression?.let(::visit)
    return out
}

private fun KotlinSemanticChecks.noExpectedReturnType(holder: KtElement, call: KtCallExpression?, resolver: KotlinResolver): Boolean {
    val p = holder.parent
    if (p is KtProperty) return p.initializer === holder && p.typeReference == null && !p.hasDelegate()
    call ?: return false
    if (call.typeArgumentList != null) return false
    val sym = runCatching { resolver.callTargets(call) }.getOrDefault(emptyList())
        .filter { it.kind == SymbolKind.METHOD }.distinctBy { it.typeParameters to it.paramTypes.size }.singleOrNull() ?: return false
    if (sym.typeParameters.isEmpty()) return false
    // The function-typed parameter the lambda fills: the last one for a trailing lambda, else by position or name.
    val args = call.valueArguments
    val arg = holder.parent as? KtValueArgument ?: return false
    val named = arg.getArgumentName()?.asName?.identifier
    val idx = when {
        named != null -> sym.paramNames.indexOf(named)
        arg is KtLambdaArgument -> sym.paramTypes.lastIndex
        else -> args.indexOf(arg)
    }
    if (sym.varargParamIndex >= 0) return false
    val pt = sym.paramTypes.getOrNull(idx) as? KotlinType ?: return false
    if (!pt.qualifiedName.startsWith("kotlin.Function") || pt.isExtensionFunctionType && pt.typeArguments.size < 2) return false
    val result = pt.typeArguments.lastOrNull() as? KotlinType ?: return false
    val r = result.qualifiedName
    if (result.nullable || r !in sym.typeParameters) return false
    if (sym.typeParamBoundNames.any { it != null }) return false
    // Nothing else may fix it: no other argument's parameter, nor the receiver, mentions it.
    fun mentions(t: dev.ide.lang.resolve.TypeRef?): Boolean =
        t != null && (t.qualifiedName == r || t.typeArguments.any { mentions(it) })
    if (sym.receiverTypeArgs.any { mentions(it) } || sym.receiverTypeParam == r) return false
    for ((i, other) in args.withIndex()) {
        if (other === arg) continue
        val on = other.getArgumentName()?.asName?.identifier
        val oi = if (on != null) sym.paramNames.indexOf(on) else if (other is KtLambdaArgument) sym.paramTypes.lastIndex else i
        if (mentions(sym.paramTypes.getOrNull(oi) ?: return false)) return false
    }
    // And no expected type reaches the call's result.
    if (!mentions(sym.type)) return true
    val q = (call.parent as? KtQualifiedExpression)?.takeIf { it.selectorExpression === call }
    return discardedOrUntypedPosition(q ?: call)
}
