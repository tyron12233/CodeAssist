package com.tyron.builder.internal.buildtree;

import com.tyron.builder.internal.build.ExecutionResult;

/**
 * Responsible for running all scheduled work for the build tree.
 */
public interface BuildTreeWorkExecutor {
    /**
     * Runs the scheduled work and returns a result object containing any failures.
     */
    ExecutionResult<Void> execute(BuildTreeWorkGraph graph);
}
