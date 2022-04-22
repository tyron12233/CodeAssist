package com.tyron.builder.execution;

import com.tyron.builder.api.Task;
import com.tyron.builder.execution.TaskSelector;
import com.tyron.builder.execution.plan.ExecutionPlan;
import com.tyron.builder.api.internal.GradleInternal;
import com.tyron.builder.util.Predicates;
import com.tyron.builder.initialization.TaskSchedulingPreparer;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Predicate;

/**
 * A {@link BuildConfigurationAction} which filters excluded tasks.
 */
public class ExcludedTaskFilteringProjectsPreparer implements TaskSchedulingPreparer {
    private final TaskSelector taskSelector;

    public ExcludedTaskFilteringProjectsPreparer(TaskSelector taskSelector) {
        this.taskSelector = taskSelector;
    }

    @Override
    public void prepareForTaskScheduling(GradleInternal gradle, ExecutionPlan executionPlan) {
        Set<String> excludedTaskNames = gradle.getStartParameter().getExcludedTaskNames();
        if (!excludedTaskNames.isEmpty()) {
            final Set<Predicate<Task>> filters = new HashSet<>();
            for (String taskName : excludedTaskNames) {
                filters.add(taskSelector.getFilter(taskName));
            }
            executionPlan.useFilter(Predicates.intersect(filters));
        }
    }
}
