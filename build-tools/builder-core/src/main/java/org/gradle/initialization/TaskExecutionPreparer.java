package org.gradle.initialization;

import org.gradle.execution.BuildWorkExecutor;
import org.gradle.execution.plan.ExecutionPlan;
import org.gradle.api.internal.GradleInternal;

/**
 * Responsible for preparing `Gradle` instances for task execution. The result is passed to a {@link BuildWorkExecutor} for execution.
 * Prior to preparing for task execution, the `Gradle` instance has its projects configured by a {@link ProjectsPreparer}.
 *
 * <p>This includes resolving the entry tasks and calculating the task graph.</p>
 */
public interface TaskExecutionPreparer {
    void prepareForTaskExecution(GradleInternal gradle, ExecutionPlan plan);
}