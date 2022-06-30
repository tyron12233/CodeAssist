package com.tyron.builder.api.internal.tasks.execution;

import com.tyron.builder.api.NonNullApi;
import com.tyron.builder.api.internal.TaskInternal;
import com.tyron.builder.api.internal.changedetection.TaskExecutionMode;
import com.tyron.builder.api.internal.changedetection.TaskExecutionModeResolver;
import com.tyron.builder.api.internal.tasks.TaskExecuter;
import com.tyron.builder.api.internal.tasks.TaskExecuterResult;
import com.tyron.builder.api.internal.tasks.TaskExecutionContext;
import com.tyron.builder.api.internal.tasks.TaskStateInternal;
import com.tyron.builder.internal.time.Time;
import com.tyron.builder.internal.time.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@NonNullApi
public class ResolveTaskExecutionModeExecuter implements TaskExecuter {
    private static final Logger LOGGER = LoggerFactory.getLogger(ResolveTaskExecutionModeExecuter.class);

    private final TaskExecuter executer;
    private final TaskExecutionModeResolver executionModeResolver;

    public ResolveTaskExecutionModeExecuter(TaskExecutionModeResolver executionModeResolver, TaskExecuter executer) {
        this.executer = executer;
        this.executionModeResolver = executionModeResolver;
    }

    @Override
    public TaskExecuterResult execute(final TaskInternal task, TaskStateInternal state, final TaskExecutionContext context) {
        Timer clock = Time.startTimer();
        TaskExecutionMode taskExecutionMode = executionModeResolver.getExecutionMode(task, context.getTaskProperties());
        context.setTaskExecutionMode(taskExecutionMode);
        LOGGER.debug("Putting task artifact state for {} into context took {}.", task, clock.getElapsed());
        try {
            return executer.execute(task, state, context);
        } finally {
            context.setTaskExecutionMode(null);
            LOGGER.debug("Removed task artifact state for {} from context.", task);
        }
    }

}
