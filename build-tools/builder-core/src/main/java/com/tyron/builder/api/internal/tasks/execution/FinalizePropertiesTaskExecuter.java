package com.tyron.builder.api.internal.tasks.execution;

import com.tyron.builder.api.internal.TaskInternal;
import com.tyron.builder.api.internal.tasks.TaskExecuter;
import com.tyron.builder.api.internal.tasks.TaskExecuterResult;
import com.tyron.builder.api.internal.tasks.TaskExecutionContext;
import com.tyron.builder.api.internal.tasks.TaskStateInternal;
import com.tyron.builder.api.internal.tasks.properties.LifecycleAwareValue;
import com.tyron.builder.api.internal.tasks.properties.TaskProperties;

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
