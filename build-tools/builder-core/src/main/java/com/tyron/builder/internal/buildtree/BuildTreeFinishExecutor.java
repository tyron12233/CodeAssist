package com.tyron.builder.internal.buildtree;

import javax.annotation.Nullable;
import java.util.List;

public interface BuildTreeFinishExecutor {
    /**
     * Finishes any work and runs any pending user clean up code such as build finished hooks, build service cleanup and so on.
     *
     * @param failures The failures to report to the build finished hooks.
     * @return The exception to throw, packages up the given failures plus any failures finishing the build.
     */
    @Nullable
    RuntimeException finishBuildTree(List<Throwable> failures);
}