package org.gradle.execution;

import org.gradle.api.Task;
import org.gradle.execution.TaskSelector;
import org.gradle.execution.plan.ExecutionPlan;
import org.gradle.api.internal.GradleInternal;
import org.gradle.util.Predicates;
import org.gradle.initialization.TaskSchedulingPreparer;

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
