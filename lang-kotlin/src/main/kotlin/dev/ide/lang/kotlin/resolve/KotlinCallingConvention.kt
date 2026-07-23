package dev.ide.lang.kotlin.resolve

import dev.ide.lang.kotlin.symbols.TypeRendering
import com.intellij.psi.PsiElement
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.KtNamedFunction

/** Calling-convention context: whether a position is @Composable or suspend, by walking the PSI boundaries. */

/**
 * Whether the calling context at [offset] is a `@Composable` context, per Compose's calling convention
 * (the rule the Compose compiler's `ComposableCallChecker` enforces):
 *  - a `@Composable` function body (or a `@Composable` property accessor) → [ComposableContext.COMPOSABLE];
 *  - a lambda whose expected type is `@Composable` (a content slot like `setContent`/`Column`) → COMPOSABLE;
 *  - a plain (non-inline) lambda is its own non-composable boundary → [ComposableContext.NON_COMPOSABLE];
 *  - an `inline` lambda (`repeat`/`with`/`forEach`/`let`…) is transparent — composability flows through it
 *    from the enclosing scope, so the walk continues outward;
 *  - the file/top level → NON_COMPOSABLE.
 * [ComposableContext.UNKNOWN] when a lambda's expected type AND callee both fail to resolve (the parse-only
 * model can't tell) — callers should back off (no diagnostic, no completion boost) to avoid false positives.
 */
fun KotlinResolver.composableContextAt(offset: Int): ComposableContext {
    // The walk is decided entirely by the boundary nodes (function/accessor/lambda) above the offset, so the
    // nearest such boundary is a sound cache key — every position within it resolves to the same context.
    val boundary = run {
        var n: PsiElement? = elementAt(offset)
        while (n != null && n !is KtNamedFunction && n !is org.jetbrains.kotlin.psi.KtPropertyAccessor && n !is KtLambdaExpression) n =
            n.parent
        n
    }
    boundary?.let { composeCtxCache[it]?.let { hit -> return hit } }
    return composableContextWalk(offset).also {
        if (boundary != null) composeCtxCache[boundary] = it
    }
}

internal fun KotlinResolver.composableContextWalk(offset: Int): ComposableContext {
    var node: PsiElement? = elementAt(offset)
    while (node != null) {
        when (node) {
            is KtNamedFunction ->
                return if (node.hasComposableAnnotation()) ComposableContext.COMPOSABLE else ComposableContext.NON_COMPOSABLE

            is org.jetbrains.kotlin.psi.KtPropertyAccessor ->
                if (node.hasComposableAnnotation()) return ComposableContext.COMPOSABLE

            is KtLambdaExpression -> {
                val expected = expectedFunctionTypeFor(node)
                if (expected?.isComposable == true) return ComposableContext.COMPOSABLE
                val callee =
                    enclosingCallAndParamIndex(node)?.let { resolveCalleeFunction(it.first) }
                // Couldn't determine what kind of lambda this is → unknown context (back off downstream).
                if (expected == null && callee == null) return ComposableContext.UNKNOWN
                // A non-inline lambda resets the context; an inline lambda is transparent (keep walking out).
                if (callee?.isInline != true) return ComposableContext.NON_COMPOSABLE
            }

            else -> {}
        }
        node = node.parent
    }
    return ComposableContext.NON_COMPOSABLE
}

private fun org.jetbrains.kotlin.psi.KtAnnotated.hasComposableAnnotation(): Boolean =
    annotationEntries.any { it.shortName?.asString() == "Composable" }

/**
 * Whether the calling context at [offset] is a `suspend` context, per Kotlin's coroutine calling convention
 * (the rule the compiler's coroutine checker enforces: a suspend function may be called only from another
 * suspend function or a suspend lambda):
 *  - a `suspend` function body → [SuspendContext.SUSPEND]; a plain function / property accessor body → NON_SUSPEND;
 *  - a lambda whose expected type is a `suspend (…) -> R` (`kotlin.SuspendFunctionN`) → SUSPEND;
 *  - an `inline` lambda (`repeat`/`with`/`forEach`/`let`/`coroutineScope`…) is transparent: suspend-ness
 *    flows through it from the enclosing scope, so the walk continues outward;
 *  - a non-inline lambda filling a SOURCE callee's plainly-non-suspend functional parameter → NON_SUSPEND;
 *  - the file/top level → NON_SUSPEND.
 * [SuspendContext.UNKNOWN] (callers back off) when a lambda's suspend-ness can't be trusted. Binary suspend
 * parameters ARE normally recovered: the metadata decoder rewrites a `suspend (…) -> R` (stored JVM-lowered as
 * a continuation-expanded `FunctionN` with the `isSuspend` flag) back to `kotlin.SuspendFunctionN`, so a freshly
 * decoded `launch`/`withContext`/`flow { }` lambda is detected as SUSPEND above. But a STALE persistent-cache
 * entry written before that rewrite still carries the lowered `FunctionN`, so a non-inline lambda filling a
 * BINARY callee's functional parameter that is NOT a `SuspendFunctionN` could still secretly be a suspend
 * lambda; reporting NON_SUSPEND there would false-positive on the most common coroutine pattern. Hence only a
 * SOURCE callee's function-type FQN is trusted for NON_SUSPEND; an unconfirmed binary one stays UNKNOWN.
 */
fun KotlinResolver.suspendContextAt(offset: Int): SuspendContext {
    // The nearest enclosing function/accessor/lambda fully decides the context (every position within it
    // resolves the same), so it is a sound cache key (mirrors [composableContextAt]).
    val boundary = run {
        var n: PsiElement? = elementAt(offset)
        while (n != null && n !is KtNamedFunction && n !is org.jetbrains.kotlin.psi.KtPropertyAccessor && n !is KtLambdaExpression) n =
            n.parent
        n
    }
    boundary?.let { suspendCtxCache[it]?.let { hit -> return hit } }
    return suspendContextWalk(offset).also { if (boundary != null) suspendCtxCache[boundary] = it }
}

internal fun KotlinResolver.suspendContextWalk(offset: Int): SuspendContext {
    var node: PsiElement? = elementAt(offset)
    while (node != null) {
        when (node) {
            is KtNamedFunction ->
                return if (node.hasModifier(KtTokens.SUSPEND_KEYWORD)) SuspendContext.SUSPEND else SuspendContext.NON_SUSPEND
            // Property accessors (and field initializers) are never suspend; they're a non-suspend boundary.
            is org.jetbrains.kotlin.psi.KtPropertyAccessor -> return SuspendContext.NON_SUSPEND
            is KtLambdaExpression -> {
                val expected = expectedFunctionTypeFor(node)
                if (expected != null && TypeRendering.isSuspendFunctionType(expected.qualifiedName)) return SuspendContext.SUSPEND
                val callee =
                    enclosingCallAndParamIndex(node)?.let { resolveCalleeFunction(it.first) }
                when {
                    // An inline lambda is transparent: keep walking out to the real enclosing boundary.
                    callee?.isInline == true -> {}
                    // A SOURCE callee's functional-parameter FQN is faithful: a non-`SuspendFunctionN` type is
                    // genuinely a plain lambda, so this lambda is its own non-suspend boundary.
                    callee != null && callee.origin.fromSource && expected != null -> return SuspendContext.NON_SUSPEND
                    // Binary callee (suspend marker may be lost) or unresolved → can't tell; back off.
                    else -> return SuspendContext.UNKNOWN
                }
            }

            else -> {}
        }
        node = node.parent
    }
    return SuspendContext.NON_SUSPEND
}
