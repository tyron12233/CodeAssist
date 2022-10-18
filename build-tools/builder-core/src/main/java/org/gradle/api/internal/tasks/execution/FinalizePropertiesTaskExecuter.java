package org.gradle.api.internal.tasks.execution;

import org.gradle.api.internal.TaskInternal;
import org.gradle.api.internal.tasks.TaskExecuter;
import org.gradle.api.internal.tasks.TaskExecuterResult;
import org.gradle.api.internal.tasks.TaskExecutionContext;
import org.gradle.api.internal.tasks.TaskStateInternal;
import org.gradle.api.internal.tasks.properties.LifecycleAwareValue;
import org.gradle.api.internal.tasks.properties.TaskProperties;

public class FinalizePropertiesTaskExecuter implements TaskExecuter {
    private final TaskExecuter executer;

    public FinalizePropertiesTaskExecuter(TaskExecuter executer) {
        this.executer = executer;
    }

    @Override
    public TaskExecuterResult execute(TaskInternal task,
                                      TaskStateInternal state,
                                      TaskExecutionContext context) {
        TaskProperties properties = context.getTaskProperties();
        for (LifecycleAwareValue value : properties.getLifecycleAwareValues()) {
            value.prepareValue();
        }

        try {
            return executer.execute(task, state, context);
        } finally {
            for (LifecycleAwareValue value : properties.getLifecycleAwareValues()) {
                value.cleanupValue();
            }
        }
    }
}
