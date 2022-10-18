package org.gradle.execution;

import org.gradle.execution.plan.ExecutionPlan;
import org.gradle.api.internal.GradleInternal;
import org.gradle.initialization.TaskSchedulingPreparer;

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

