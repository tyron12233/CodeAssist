package com.tyron.builder.api.internal.tasks;

import com.tyron.builder.api.internal.TaskInternal;

public interface TaskExecuter {
    /**
     * Executes the given task. If the task fails with an exception, the exception is packaged in the provided task
     * state.
     */
    TaskExecuterResult execute(TaskInternal task, TaskStateInternal state, TaskExecutionContext context);
}
