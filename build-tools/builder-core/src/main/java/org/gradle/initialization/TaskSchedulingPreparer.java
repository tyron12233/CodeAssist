package org.gradle.initialization;

import org.gradle.execution.plan.ExecutionPlan;
import org.gradle.api.internal.GradleInternal;

public interface TaskSchedulingPreparer {
    void prepareForTaskScheduling(GradleInternal gradle, ExecutionPlan executionPlan);
}