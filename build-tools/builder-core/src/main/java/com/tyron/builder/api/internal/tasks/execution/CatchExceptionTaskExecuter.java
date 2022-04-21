package com.tyron.builder.api.internal.tasks.execution;

import com.tyron.builder.api.internal.TaskInternal;
import com.tyron.builder.api.internal.tasks.TaskExecuter;
import com.tyron.builder.api.internal.tasks.TaskExecuterResult;
import com.tyron.builder.api.internal.tasks.TaskExecutionContext;
import com.tyron.builder.api.tasks.TaskExecutionException;
import com.tyron.builder.api.internal.tasks.TaskStateInternal;

public class CatchExceptionTaskExecuter implements TaskExecuter {
    private final TaskExecuter executer;

    public CatchExceptionTaskExecuter(TaskExecuter executer) {
        this.executer = executer;
    }

    @Override
    public TaskExecuterResult execute(TaskInternal task,
                                      TaskStateInternal state,
                                      TaskExecutionContext context) {
        try {
            return executer.execute(task, state, context);
        } catch (RuntimeException e) {
            state.setOutcome(new TaskExecutionException(task, e));
            return TaskExecuterResult.WITHOUT_OUTPUTS;
        }
    }
}
