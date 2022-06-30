package com.tyron.builder.internal.build;


import com.tyron.builder.internal.buildtree.BuildTreeLifecycleController;

import java.util.function.Function;

/**
 * A build which can be the target of a build action.
 */
public interface BuildActionTarget extends BuildState {
    /**
     * Runs a single invocation of this build, executing the given action and returning the result. Should be called once only for a given build instance.
     */
    <T> T run(Function<? super BuildTreeLifecycleController, T> buildAction);
}