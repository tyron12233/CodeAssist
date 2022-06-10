package org.gradle.api.internal.tasks.execution;

import org.gradle.api.internal.TaskInternal;
import org.gradle.api.internal.tasks.TaskExecuter;
import org.gradle.api.internal.tasks.TaskExecuterResult;
import org.gradle.api.internal.tasks.TaskExecutionContext;
import org.gradle.api.tasks.TaskExecutionException;
import org.gradle.api.internal.tasks.TaskStateInternal;

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
