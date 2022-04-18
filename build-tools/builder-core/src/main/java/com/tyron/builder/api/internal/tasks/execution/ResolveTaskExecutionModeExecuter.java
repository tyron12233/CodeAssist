package com.tyron.builder.api.internal.tasks.execution;

import com.tyron.builder.StartParameter;
import com.tyron.builder.api.internal.TaskInternal;
import com.tyron.builder.api.internal.changedetection.TaskExecutionMode;
import com.tyron.builder.api.internal.changedetection.TaskExecutionModeResolver;
import com.tyron.builder.api.internal.changedetection.changes.DefaultTaskExecutionModeResolver;
import com.tyron.builder.api.internal.tasks.TaskExecuter;
import com.tyron.builder.api.internal.tasks.TaskExecuterResult;
import com.tyron.builder.api.internal.tasks.TaskExecutionContext;
import com.tyron.builder.api.internal.tasks.TaskStateInternal;
import com.tyron.builder.api.internal.time.Time;
import com.tyron.builder.api.internal.time.Timer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ResolveTaskExecutionModeExecuter implements TaskExecuter {

    private static final Logger LOGGER = LoggerFactory.getLogger(ResolveTaskExecutionModeExecuter.class);
    private final TaskExecuter executer;
    private final TaskExecutionModeResolver resolver = new DefaultTaskExecutionModeResolver(new StartParameter());

    public ResolveTaskExecutionModeExecuter(TaskExecuter executer) {
        this.executer = executer;
    }

    @Override
    public TaskExecuterResult execute(TaskInternal task,
                                      TaskStateInternal state,
                                      TaskExecutionContext context) {
        Timer clock = Time.startTimer();
        TaskExecutionMode taskExecutionMode = resolver.getExecutionMode(task, context.getTaskProperties());
        context.setTaskExecutionMode(taskExecutionMode);
        try {
            return executer.execute(task, state, context);
        } finally {
            context.setTaskExecutionMode(null);
            LOGGER.debug("Removed task artifact state for {} from context", task);
        }
    }
}
