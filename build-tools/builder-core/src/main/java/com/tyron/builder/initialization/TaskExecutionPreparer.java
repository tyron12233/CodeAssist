package com.tyron.builder.initialization;

import com.tyron.builder.execution.BuildWorkExecutor;
import com.tyron.builder.execution.plan.ExecutionPlan;
import com.tyron.builder.api.internal.GradleInternal;

/**
 * Responsible for preparing `Gradle` instances for task execution. The result is passed to a {@link BuildWorkExecutor} for execution.
 * Prior to preparing for task execution, the `Gradle` instance has its projects configured by a {@link ProjectsPreparer}.
 *
 * <p>This includes resolving the entry tasks and calculating the task graph.</p>
 */
public interface TaskExecutionPreparer {
    void prepareForTaskExecution(GradleInternal gradle, ExecutionPlan plan);
}