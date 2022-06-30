package com.tyron.builder.internal.build;

import com.tyron.builder.internal.buildtree.BuildTreeLifecycleController;

import java.util.function.Function;

public interface NestedBuildTree {
    /**
     * Runs a single invocation of this build, executing the given action and returning the result. Should be called once only for a given build tree instance.
     */
    <T> T run(Function<? super BuildTreeLifecycleController, T> buildAction);
}