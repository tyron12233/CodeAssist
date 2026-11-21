package dev.ide.lang.kotlin

import dev.ide.kotlin.syntax.psi.KtBlockExpression
import dev.ide.kotlin.syntax.psi.KtCallExpression
import dev.ide.kotlin.syntax.psi.KtClassOrObject
import dev.ide.kotlin.syntax.psi.KtElement
import dev.ide.kotlin.syntax.psi.KtFunctionLiteral
import dev.ide.kotlin.syntax.psi.KtFunctionType
import dev.ide.kotlin.syntax.psi.KtLambdaExpression
import dev.ide.kotlin.syntax.psi.KtNameReferenceExpression
import dev.ide.kotlin.syntax.psi.KtNamedDeclaration
import dev.ide.kotlin.syntax.psi.KtNamedFunction
import dev.ide.kotlin.syntax.psi.KtParameter
import dev.ide.kotlin.syntax.psi.KtParenthesizedExpression
import dev.ide.kotlin.syntax.psi.KtTokens
import dev.ide.lang.dom.Diagnostic
import dev.ide.lang.dom.Severity
import dev.ide.lang.dom.TextRange

/**
 * NON_LOCAL_RETURN_NOT_ALLOWED: an inline function's inlinable lambda parameter called from code that is not
 * inlined into the function's body, ported from `FirInlineBodyResolvableExpressionChecker` (its
 * `isNonLocalReturnAllowed` test on the declarations around the call). A `return` in the lambda would have to
 * leave the inline function's caller from inside that code, which it cannot.
 *
 * Only the boundaries that are certainly not inlined are judged: an object literal or local class, a local
 * function, and a lambda invoked on the spot (`{ p() }()`). A lambda passed to some other function is inlined
 * when that function is inline, which needs the callee resolved, so it is left alone. A `crossinline`
 * parameter has already given up non-local returns and is fine anywhere.
 */
internal object KotlinNonLocalReturnChecks {

    fun check(fn: KtNamedFunction): List<Diagnostic> {
        if (!fn.hasModifier(KtTokens.INLINE_KEYWORD)) return emptyList()
        val body = fn.bodyExpression ?: return emptyList()
        val params = fn.valueParameters.filter(::inlinableWithNonLocalReturns).mapNotNull { it.name }.toSet()
        if (params.isEmpty()) return emptyList()
        val out = ArrayList<Diagnostic>()
        fun visit(e: KtElement) {
            if (e is KtCallExpression) {
                val callee = e.calleeExpression as? KtNameReferenceExpression
                val name = callee?.getReferencedName()
                if (callee != null && name in params && crossesNonInlinedBoundary(e, fn, name!!)) {
                    val r = callee.textRange
                    out += Diagnostic(
                        TextRange(r.startOffset, r.endOffset), Severity.ERROR,
                        "Can't inline '$name' here: it may contain non-local returns. Add 'crossinline' modifier to parameter declaration '$name'",
                        KotlinDiagnosticCodes.NON_LOCAL_RETURN_NOT_ALLOWED,
                    )
                }
            }
            for (c in e.children) visit(c)
        }
        visit(body)
        return out
    }

    /** Inlined, and allowed non-local returns: not `noinline`, `crossinline` or `vararg`, of a function type. */
    private fun inlinableWithNonLocalReturns(p: KtParameter): Boolean {
        if (p.hasModifier(KtTokens.NOINLINE_KEYWORD) || p.hasModifier(KtTokens.CROSSINLINE_KEYWORD) || p.isVarArg) return false
        return p.typeReference?.typeElement is KtFunctionType
    }

    /** Whether the call [call] of [name] sits in a non-inlined declaration inside [fn], with nothing in between
     *  declaring another [name]. */
    private fun crossesNonInlinedBoundary(call: KtCallExpression, fn: KtNamedFunction, name: String): Boolean {
        var crossed = false
        var child: KtElement = call
        var p = call.parent
        while (p != null && p !== fn) {
            when (p) {
                is KtClassOrObject -> {
                    if (p.declarations.any { it is KtNamedDeclaration && it.name == name }) return false
                    crossed = true
                }
                is KtNamedFunction -> {
                    if (p.valueParameters.any { it.name == name } || p.name == name) return false
                    // An anonymous function is inlined like a lambda when passed to an inline function.
                    if (p.name != null) crossed = true
                }
                is KtFunctionLiteral -> if (p.valueParameters.any { it.name == name }) return false
                is KtLambdaExpression -> if (invokedOnTheSpot(p)) crossed = true
                is KtBlockExpression -> if (p.statements.any { it !== child && it is KtNamedDeclaration && it.name == name }) return false
            }
            child = p
            p = p.parent
        }
        return p === fn && crossed
    }

    /** `{ … }()`: the lambda is the callee of a call, possibly parenthesized. */
    private fun invokedOnTheSpot(lambda: KtLambdaExpression): Boolean {
        var e: KtElement = lambda
        while (e.parent is KtParenthesizedExpression) e = e.parent!!
        val call = e.parent as? KtCallExpression ?: return false
        return call.calleeExpression === e
    }
}
