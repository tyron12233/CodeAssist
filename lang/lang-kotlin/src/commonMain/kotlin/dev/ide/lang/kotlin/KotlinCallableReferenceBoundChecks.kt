package dev.ide.lang.kotlin

import dev.ide.kotlin.syntax.psi.KtCallExpression
import dev.ide.kotlin.syntax.psi.KtCallableReferenceExpression
import dev.ide.kotlin.syntax.psi.KtNameReferenceExpression
import dev.ide.lang.dom.Diagnostic
import dev.ide.lang.kotlin.analysis.KotlinChecker
import dev.ide.lang.kotlin.resolve.KotlinResolver
import dev.ide.lang.kotlin.resolve.constructorTypeFqn

/*
 * UPPER_BOUND_VIOLATED in the type on the left of `::` (`Foo<String>::value` for `class Foo<T : Number>`). That
 * type parses as an argument-less call, which every call check skips, so its type arguments were never held
 * against their bounds; K2 checks them as it does any type reference (FirUpperBoundViolatedExpressionChecker).
 *
 * Only a class named bare whose bounds are plain classifiers (no type arguments): a bound that names another type
 * parameter (`T : Element<K>`) depends on the outer arguments, which this does not substitute.
 */

internal fun KotlinSemanticChecks.callableReferenceBoundCheckers(): List<KotlinChecker> = listOf(
    KotlinChecker(KtCallableReferenceExpression::class) { psi ->
        KotlinPerf.span("sem.callableRefBounds") {
            if (resolveReady) reportAll(callableReferenceLhsBounds(psi as KtCallableReferenceExpression, resolver))
        }
    },
)

private fun KotlinSemanticChecks.callableReferenceLhsBounds(ref: KtCallableReferenceExpression, resolver: KotlinResolver): List<Diagnostic> {
    val lhs = ref.receiverExpression as? KtCallExpression ?: return emptyList()
    if (lhs.valueArgumentList != null || lhs.lambdaArguments.isNotEmpty()) return emptyList()
    val args = lhs.typeArgumentList?.arguments ?: return emptyList()
    if (args.isEmpty()) return emptyList()
    val callee = lhs.calleeExpression as? KtNameReferenceExpression ?: return emptyList()
    val fqn = resolver.constructorTypeFqn(callee.getReferencedName(), callee.textRange.startOffset) ?: return emptyList()
    val bounds = service.classTypeParameterBounds(fqn)
    if (bounds.size != args.size) return emptyList()
    val plain = bounds.map { b -> b?.takeIf { it.typeArguments.isEmpty() && !it.isTypeParameter } }
    if (plain.all { it == null }) return emptyList()
    return checkArgsAgainstBounds(args, plain, resolver)
}
