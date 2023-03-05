package org.gradle.api.internal.changedetection.changes;

import org.gradle.api.InvalidUserCodeException;
import org.gradle.StartParameter;
import org.gradle.api.internal.TaskInternal;
import org.gradle.api.internal.changedetection.TaskExecutionMode;
import org.gradle.api.internal.changedetection.TaskExecutionModeResolver;
import org.gradle.api.internal.project.taskfactory.AbstractIncrementalTaskAction;
import org.gradle.api.internal.tasks.InputChangesAwareTaskAction;
import org.gradle.api.internal.tasks.properties.TaskProperties;
import org.gradle.util.Predicates;

import java.util.function.Predicate;

public class DefaultTaskExecutionModeResolver implements TaskExecutionModeResolver {

    private final StartParameter startParameter;

    public DefaultTaskExecutionModeResolver(StartParameter startParameter) {
        this.startParameter = startParameter;
    }

    @Override
    public TaskExecutionMode getExecutionMode(TaskInternal task, TaskProperties properties) {
        if (task.getReasonNotToTrackState().isPresent()) {
            return TaskExecutionMode.UNTRACKED;
        }
        // Only false if no declared outputs AND no Task.upToDateWhen spec. We force to true for incremental tasks.
        Predicate<? super TaskInternal> upToDateSpec = task.getOutputs().getUpToDateSpec();
        if (!properties.hasDeclaredOutputs() && upToDateSpec == Predicates.satisfyNone()) {
            if (requiresInputChanges(task)) {
                throw new InvalidUserCodeException("You must declare outputs or use `TaskOutputs.upToDateWhen()` when using the incremental task API");
            } else {
                return TaskExecutionMode.NO_OUTPUTS;
            }
        }

        if (startParameter.isRerunTasks()) {
            return TaskExecutionMode.RERUN_TASKS_ENABLED;
        }

        if (!upToDateSpec.test(task)) {
            return TaskExecutionMode.UP_TO_DATE_WHEN_FALSE;
        }

        return TaskExecutionMode.INCREMENTAL;
    }

    private static boolean requiresInputChanges(TaskInternal task) {
        for (InputChangesAwareTaskAction taskAction : task.getTaskActions()) {
            if (taskAction instanceof AbstractIncrementalTaskAction) {
                return true;
            }
        }
        return false;
    }
}