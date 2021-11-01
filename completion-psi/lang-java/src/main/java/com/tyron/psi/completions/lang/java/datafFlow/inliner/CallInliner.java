package com.tyron.psi.completions.lang.java.datafFlow.inliner;

import com.tyron.psi.completions.lang.java.datafFlow.CFGBuilder;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.com.intellij.psi.PsiExpression;
import org.jetbrains.kotlin.com.intellij.psi.PsiMethodCallExpression;

/**
 * A CallInliner can recognize specific method calls and inline their implementation into current CFG
 */
public interface CallInliner {
    /**
     * Try to inline the supplied call
     *
     * @param builder a builder to use for inlining. Current state is before given method call (call arguments and qualifier are not
     *                handled yet).
     * @param call    a call to inline
     * @return true if inlining is successful. In this case subsequent inliners are skipped and default processing is omitted.
     * If false is returned, inliner must not emit any instructions via builder.
     */
    boolean tryInlineCall(@NotNull CFGBuilder builder, @NotNull PsiMethodCallExpression call);

    /**
     * @param expression expression to test
     * @return true if this inliner may add constraints on the precise type of given expression
     */
    default boolean mayInferPreciseType(@NotNull PsiExpression expression) {
        return false;
    }
}
