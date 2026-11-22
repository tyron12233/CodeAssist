package dev.ide.lang.kotlin

import dev.ide.kotlin.syntax.psi.KtCallExpression
import dev.ide.kotlin.syntax.psi.KtElement
import dev.ide.kotlin.syntax.psi.KtLambdaExpression
import dev.ide.kotlin.syntax.psi.KtNameReferenceExpression
import dev.ide.kotlin.syntax.psi.KtQualifiedExpression
import dev.ide.lang.kotlin.resolve.KotlinResolver
import dev.ide.lang.kotlin.resolve.bareNameResolves
import dev.ide.lang.kotlin.resolve.callTargets
import dev.ide.lang.kotlin.resolve.companionMemberInScope
import dev.ide.lang.kotlin.resolve.constructorTypeFqn
import dev.ide.lang.kotlin.resolve.contextualFunctionType
import dev.ide.lang.kotlin.resolve.enclosingCallAndParamIndex
import dev.ide.lang.kotlin.resolve.infixLambdaFunctionType
import dev.ide.lang.kotlin.resolve.lambdaParamIndex
import dev.ide.lang.kotlin.symbols.KotlinType

/*
 * The implicit `it` of a lambda exists only when the function type the lambda is checked against has exactly one
 * value parameter (a receiver does not count): `bar2 { it }` with `bar2(f: () -> Int)` and
 * `val x: String.() -> String = { it }` name nothing, which K2 reports as UNRESOLVED_REFERENCE. A lambda passed to
 * a call nothing resolves (`Unknown(k) { it }`) is analyzed with no expected type at all, so it has no `it`
 * either. An enclosing lambda's `it` is still visible through one that declares none.
 */

internal enum class ImplicitIt { AVAILABLE, MISSING, UNKNOWN }

/** Whether the parameterless [lambda] declares an implicit `it`, judged only when every way it can be typed
 *  agrees: a declared functional type, an infix function's parameter, or EVERY candidate of the call it is an
 *  argument of (an overload whose parameter there is not a function type, or a mix of arities, is UNKNOWN). */
internal fun KotlinSemanticChecks.implicitItOf(lambda: KtLambdaExpression, resolver: KotlinResolver): ImplicitIt {
    if (lambda.valueParameters.isNotEmpty()) return ImplicitIt.MISSING
    (resolver.contextualFunctionType(lambda) ?: resolver.infixLambdaFunctionType(lambda))?.let { ft ->
        val shape = service.functionalShape(ft) ?: return ImplicitIt.UNKNOWN
        return if (shape.parameterTypes.size == 1) ImplicitIt.AVAILABLE else ImplicitIt.MISSING
    }
    val (call, argIndex) = resolver.enclosingCallAndParamIndex(lambda) ?: return ImplicitIt.UNKNOWN
    val targets = resolver.callTargets(call)
    if (targets.isEmpty()) return if (calleeNamesNothing(call, resolver)) ImplicitIt.MISSING else ImplicitIt.UNKNOWN
    val sizes = HashSet<Int>()
    for (sym in targets) {
        val raw = sym.paramTypes.getOrNull(resolver.lambdaParamIndex(call, argIndex, sym)) as? KotlinType
            ?: return ImplicitIt.UNKNOWN
        if (raw.isTypeParameter) return ImplicitIt.UNKNOWN
        val shape = service.functionalShape(raw) ?: return ImplicitIt.UNKNOWN
        sizes += shape.parameterTypes.size
    }
    return when {
        sizes.all { it == 1 } -> ImplicitIt.AVAILABLE
        sizes.none { it == 1 } -> ImplicitIt.MISSING
        else -> ImplicitIt.UNKNOWN
    }
}

/** A bare callee (`Unknown(k) { … }`) that names no function, value, class or alias in scope. */
private fun KotlinSemanticChecks.calleeNamesNothing(call: KtCallExpression, resolver: KotlinResolver): Boolean {
    if (call.parent is KtQualifiedExpression && (call.parent as KtQualifiedExpression).selectorExpression === call) return false
    val callee = call.calleeExpression as? KtNameReferenceExpression ?: return false
    val name = callee.getReferencedName()
    if (name.isEmpty()) return false
    val off = callee.textRange.startOffset
    if (resolver.bareNameResolves(name, off) || resolver.companionMemberInScope(name, off)) return false
    if (resolver.constructorTypeFqn(name, off) != null) return false
    if (service.hasLibraryType(name) || service.hasProjectSourceType(name) || service.isProjectTypeAlias(name)) return false
    if (service.topLevelByName(name).isNotEmpty()) return false
    return true
}

/** Whether a bare `it` at [at] provably names no implicit lambda parameter: every enclosing lambda without
 *  declared parameters is known to declare none. Any lambda that cannot be judged keeps the answer `false`. */
internal fun KotlinSemanticChecks.implicitItProvablyAbsent(at: KtElement, resolver: KotlinResolver): Boolean {
    var node = at.parent
    while (node != null) {
        if (node is KtLambdaExpression) {
            when (implicitItOf(node, resolver)) {
                ImplicitIt.AVAILABLE, ImplicitIt.UNKNOWN -> return false
                ImplicitIt.MISSING -> {}
            }
        }
        node = node.parent
    }
    return true
}
