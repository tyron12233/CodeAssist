package org.gradle.execution;


import org.gradle.execution.plan.ExecutionPlan;
import org.gradle.api.internal.GradleInternal;
import org.gradle.internal.build.ExecutionResult;

/**
 * Executes the work scheduled for a build. Prior to execution, the work will be prepared by a {@link org.gradle.initialization.TaskExecutionPreparer}.
 */
public interface BuildWorkExecutor {
    /**
     * Executes the given work and returns the failures.
     */
    ExecutionResult<Void> execute(GradleInternal gradle, ExecutionPlan plan);
}
