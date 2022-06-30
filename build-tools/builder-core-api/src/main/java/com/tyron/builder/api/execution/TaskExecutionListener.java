package com.tyron.builder.api.execution;

import com.tyron.builder.api.Task;
import com.tyron.builder.internal.service.scopes.EventScope;
import com.tyron.builder.internal.service.scopes.Scopes;
import com.tyron.builder.api.tasks.TaskState;

/**
 * <p>A {@code TaskExecutionListener} is notified of the execution of the tasks in a build.</p>
 *
 * <p>You can add a {@code TaskExecutionListener} to a build using {@link TaskExecutionGraph#addTaskExecutionListener}
 *
 * @deprecated This type is not supported when configuration caching is enabled.
 */
@EventScope(Scopes.Build.class)
@Deprecated
public interface TaskExecutionListener {
    /**
     * This method is called immediately before a task is executed.
     *
     * @param task The task about to be executed. Never null.
     */
    void beforeExecute(Task task);

    /**
     * This method is called immediately after a task has been executed. It is always called, regardless of whether the
     * task completed successfully, or failed with an exception.
     *
     * @param task The task which was executed. Never null.
     * @param state The task state. If the task failed with an exception, the exception is available in this
     * state. Never null.
     */
    void afterExecute(Task task, TaskState state);
}