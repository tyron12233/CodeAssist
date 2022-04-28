package com.tyron.builder.execution;

import com.tyron.builder.execution.plan.ExecutionPlan;
import com.tyron.builder.api.internal.GradleInternal;
import com.tyron.builder.initialization.TaskSchedulingPreparer;

public class DefaultTaskSchedulingPreparer implements TaskSchedulingPreparer {
    private final TaskSchedulingPreparer delegate;

    public DefaultTaskSchedulingPreparer(TaskSchedulingPreparer delegate) {
        this.delegate = delegate;
    }

    @Override
    public void prepareForTaskScheduling(GradleInternal gradle, ExecutionPlan executionPlan) {
        delegate.prepareForTaskScheduling(gradle, executionPlan);
    }
}

