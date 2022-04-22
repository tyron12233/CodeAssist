package com.tyron.builder.initialization;

import com.tyron.builder.execution.plan.ExecutionPlan;
import com.tyron.builder.api.internal.GradleInternal;

public interface TaskSchedulingPreparer {
    void prepareForTaskScheduling(GradleInternal gradle, ExecutionPlan executionPlan);
}