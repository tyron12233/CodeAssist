package dev.ide.lang.kotlin

import dev.ide.kotlin.syntax.psi.KtCallExpression
import dev.ide.kotlin.syntax.psi.KtClassOrObject
import dev.ide.kotlin.syntax.psi.KtElement
import dev.ide.kotlin.syntax.psi.KtExpression
import dev.ide.kotlin.syntax.psi.KtLabeledExpression
import dev.ide.kotlin.syntax.psi.KtLambdaArgument
import dev.ide.kotlin.syntax.psi.KtLambdaExpression
import dev.ide.kotlin.syntax.psi.KtNameReferenceExpression
import dev.ide.kotlin.syntax.psi.KtNamedFunction
import dev.ide.kotlin.syntax.psi.KtParameter
import dev.ide.lang.dom.Diagnostic
import dev.ide.lang.dom.Severity
import dev.ide.lang.dom.TextRange
import dev.ide.lang.kotlin.analysis.KotlinChecker
import dev.ide.lang.kotlin.parse.KotlinDomNode
import dev.ide.lang.kotlin.parse.unwrapParen
import dev.ide.lang.kotlin.resolve.KotlinResolver
import dev.ide.lang.kotlin.resolve.callTargets
import dev.ide.lang.kotlin.resolve.calleeFunctionOf
import dev.ide.lang.kotlin.resolve.enclosingClassFqnOf
import dev.ide.lang.kotlin.symbols.KotlinType
import dev.ide.lang.kotlin.symbols.TypeRendering

/*
 * A lambda argument against the function type its parameter declares, as K2 checks it once the lambda is analyzed
 * with that type as its expected type: what it returns (RETURN_TYPE_MISMATCH on its last expression, or on a
 * `return@label`), and its shape (ARGUMENT_TYPE_MISMATCH for a lambda with no parameters where the function type
 * takes two or more, so no implicit `it` can stand for them, and for an empty body where a value is expected).
 *
 * Judged only for a call with one candidate, a function declared in project source whose parameter type is a
 * plain function type that names no type parameter: there the expected type of the lambda is written down, not
 * inferred.
 */

internal fun KotlinSemanticChecks.lambdaArgumentCheckers(): List<KotlinChecker> = listOf(
    KotlinChecker(KtCallExpression::class) { psi ->
        KotlinPerf.span("sem.ported.lambdaArgs") {
            if (resolveReady) reportAll(lambdaArgumentsAgainstFunctionTypes(psi as KtCallExpression, resolver))
            if (resolveReady) reportAll(lambdaAgainstWrittenTypeArguments(psi as KtCallExpression, resolver))
        }
    },
    // A getter's expression body is what the property's type promises (RETURN_TYPE_MISMATCH): `val x: Int get() = "s"`.
    KotlinChecker(dev.ide.kotlin.syntax.psi.KtPropertyAccessor::class) { psi ->
        psi as dev.ide.kotlin.syntax.psi.KtPropertyAccessor
        KotlinPerf.span("sem.ported.getterBody") {
            if (resolveReady && psi.isGetter && psi.returnTypeReference == null && !psi.hasBlockBody()) {
                val body = psi.bodyExpression
                val prop = psi.parent as? dev.ide.kotlin.syntax.psi.KtProperty
                val text = prop?.typeReference?.text
                if (body != null && text != null) {
                    report(valueMismatch(service.typeFromText(text, resolver.fileContext, enclosingClassFqnOf(prop)), body, resolver))
                }
            }
        }
    },
)

private fun KotlinSemanticChecks.lambdaArgumentsAgainstFunctionTypes(call: KtCallExpression, resolver: KotlinResolver): List<Diagnostic> {
    val args = call.valueArguments
    val lambdas = args.filter { a -> a.getArgumentExpression()?.let { lambdaOf(it) } != null }
    if (lambdas.isEmpty()) return emptyList()
    if (args.any { it.getSpreadElement() != null }) return emptyList()
    if (call.calleeExpression !is KtNameReferenceExpression) return emptyList()
    val targets = runCatching { resolver.callTargets(call) }.getOrDefault(emptyList())
        .distinctBy { t ->
            val at = ((t.declaration() as? KotlinDomNode)?.psi)?.textRange?.startOffset
            if (t.origin.fromSource && at != null) Pair(t.origin.file?.toString(), at) else t
        }
    if (targets.size != 1) return emptyList()
    val sym = resolver.calleeFunctionOf(call) ?: return emptyList()
    if (!sym.origin.fromSource) return emptyList()
    val fn = (sym.declaration() as? KotlinDomNode)?.psi as? KtNamedFunction ?: return emptyList()
    if (fn.nameIdentifier == null) return emptyList()
    val params = fn.valueParameters
    if (params.any { it.isVarArg }) return emptyList()
    val typeParams = typeParameterNamesAround(fn)
    val out = ArrayList<Diagnostic>()
    for ((i, arg) in args.withIndex()) {
        val expr = arg.getArgumentExpression() ?: continue
        val lambda = lambdaOf(expr) ?: continue
        val named = arg.getArgumentName()?.asName?.identifier
        val param: KtParameter = when {
            named != null -> params.firstOrNull { it.name == named }
            arg is KtLambdaArgument -> params.lastOrNull()
            else -> params.getOrNull(i)
        } ?: continue
        val text = param.typeReference?.text?.trim() ?: continue
        if (!text.startsWith("(") || text.endsWith("?") || '@' in text || text.startsWith("suspend")) continue
        if (Regex("[A-Za-z_][A-Za-z0-9_]*").findAll(text).any { it.value in typeParams }) continue
        val declared = service.typeFromText(text, resolver.fileContext, enclosingClassFqnOf(fn)) ?: continue
        if (!TypeRendering.isFunctionType(declared.qualifiedName) || declared.nullable) continue
        val arity = declared.typeArguments.size - 1 - (if (declared.isExtensionFunctionType) 1 else 0)
        val n = lambda.valueParameters.size
        if (n == 0 && arity >= 2) {
            out += mismatch(expr, "Type mismatch: a lambda without parameters is not a ${renderType(declared)}")
            continue
        }
        if (n != 0 && n != arity) continue // the parameter-count check's
        val result = declared.typeArguments.lastOrNull() as? KotlinType ?: continue
        if (lambda.bodyExpression?.statements.isNullOrEmpty() && emptyBodyMismatch(result)) {
            out += mismatch(expr, "Type mismatch: inferred type is () -> Unit but ${renderType(declared)} was expected")
            continue
        }
        lambdaAgainstFunctionType(declared, expr, resolver)?.let { out += it }
    }
    return out
}

/** Whether an empty lambda's `Unit` can never be the [result] its function type asks for. */
private fun KotlinSemanticChecks.emptyBodyMismatch(result: KotlinType): Boolean {
    if (result.isTypeParameter || result.nullable) return false
    val name = dev.ide.lang.kotlin.resolve.canonicalTypeForCheck(result).qualifiedName
    if (name == "kotlin.Unit" || name == "kotlin.Any" || name == "kotlin.Nothing") return false
    return service.isKnownType(name)
}

private fun lambdaOf(e: KtExpression): KtLambdaExpression? = when (val u = unwrapParen(e)) {
    is KtLambdaExpression -> u
    is KtLabeledExpression -> u.baseExpression?.let { unwrapParen(it) } as? KtLambdaExpression
    else -> null
}

private fun typeParameterNamesAround(fn: KtNamedFunction): Set<String> {
    val out = HashSet<String>()
    fn.typeParameters.mapNotNullTo(out) { it.name }
    var c: KtElement? = fn.parent as? KtElement
    while (c != null) {
        if (c is KtClassOrObject) c.typeParameters.mapNotNullTo(out) { it.name }
        if (c is KtNamedFunction) c.typeParameters.mapNotNullTo(out) { it.name }
        c = c.parent as? KtElement
    }
    return out
}

private fun mismatch(at: KtElement, message: String): Diagnostic {
    val r = at.textRange
    return Diagnostic(TextRange(r.startOffset, r.endOffset), Severity.ERROR, message, KotlinDiagnosticCodes.TYPE_MISMATCH)
}
