package com.tyron.builder.api.execution;


import com.tyron.builder.api.execution.plan.ExecutionPlan;
import com.tyron.builder.api.internal.GradleInternal;
import com.tyron.builder.api.internal.build.ExecutionResult;

/**
 * Executes the work scheduled for a build. Prior to execution, the work will be prepared by a {@link org.gradle.initialization.TaskExecutionPreparer}.
 */
public interface BuildWorkExecutor {
    /**
     * Executes the given work and returns the failures.
     */
    ExecutionResult<Void> execute(GradleInternal gradle, ExecutionPlan plan);
}
