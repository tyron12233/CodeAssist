package org.gradle.internal.buildtree;

import org.gradle.internal.build.ExecutionResult;

/**
 * Responsible for running all scheduled work for the build tree.
 */
public interface BuildTreeWorkExecutor {
    /**
     * Runs the scheduled work and returns a result object containing any failures.
     */
    ExecutionResult<Void> execute(BuildTreeWorkGraph graph);
}
