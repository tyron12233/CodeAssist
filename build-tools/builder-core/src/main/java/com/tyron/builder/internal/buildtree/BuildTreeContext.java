package com.tyron.builder.internal.buildtree;

import com.tyron.builder.internal.invocation.BuildAction;

public interface BuildTreeContext {
    /**
     * Runs the given action and returns the result. Failures should be packaged in the result.
     * When this method returns, all user code will have completed, including 'build finished' hooks.
     */
    BuildActionRunner.Result execute(BuildAction action);
}