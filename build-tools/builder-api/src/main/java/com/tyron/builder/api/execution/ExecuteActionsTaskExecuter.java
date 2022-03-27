package com.tyron.builder.api.execution;

import com.tyron.builder.api.Action;
import com.tyron.builder.api.Task;
import com.tyron.builder.api.internal.TaskInternal;
import com.tyron.builder.api.internal.tasks.TaskExecuter;
import com.tyron.builder.api.internal.tasks.TaskExecuterResult;
import com.tyron.builder.api.internal.tasks.TaskExecutionContext;
import com.tyron.builder.api.internal.tasks.TaskExecutionException;
import com.tyron.builder.api.internal.tasks.TaskExecutionOutcome;
import com.tyron.builder.api.internal.tasks.TaskStateInternal;

public class ExecuteActionsTaskExecuter implements TaskExecuter {
    @Override
    public TaskExecuterResult execute(TaskInternal task,
                                      TaskStateInternal state,
                                      TaskExecutionContext context) {
        state.setExecuting(true);

        try {
            for (Action<? super Task> action : task.getActions()) {
                action.execute(task);
            }
        } catch (Throwable t) {
            state.addFailure(new TaskExecutionException(task, t));
        }

        state.setExecuting(false);
        state.setDidWork(true);
        state.setOutcome(TaskExecutionOutcome.EXECUTED);
        return new TaskExecuterResult();
    }
}
