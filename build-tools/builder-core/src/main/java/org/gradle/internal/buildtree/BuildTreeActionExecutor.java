package org.gradle.internal.buildtree;

import org.gradle.internal.invocation.BuildAction;

public interface BuildTreeActionExecutor {
    /**
     * Runs the given action and returns the result. Failures should be packaged in the result.
     * When this method returns, all user code will have completed, including 'build finished' hooks.
     */
    BuildActionRunner.Result execute(BuildAction action, BuildTreeContext buildTreeContext);
}