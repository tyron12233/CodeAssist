package org.gradle.api.internal.tasks.execution;

import org.gradle.api.NonNullApi;
import org.gradle.api.internal.TaskInternal;
import org.gradle.api.internal.changedetection.TaskExecutionMode;
import org.gradle.api.internal.changedetection.TaskExecutionModeResolver;
import org.gradle.api.internal.tasks.TaskExecuter;
import org.gradle.api.internal.tasks.TaskExecuterResult;
import org.gradle.api.internal.tasks.TaskExecutionContext;
import org.gradle.api.internal.tasks.TaskStateInternal;
import org.gradle.internal.time.Time;
import org.gradle.internal.time.Timer;
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
