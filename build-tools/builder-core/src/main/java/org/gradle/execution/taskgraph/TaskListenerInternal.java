package org.gradle.execution.taskgraph;

import org.gradle.api.internal.project.taskfactory.TaskIdentity;
import org.gradle.internal.service.scopes.EventScope;
import org.gradle.internal.service.scopes.Scopes;
import org.gradle.api.tasks.TaskState;

@EventScope(Scopes.Build.class)
public interface TaskListenerInternal {
    /**
     * This method is called immediately before a task is executed.
     *
     * @param taskIdentity The identity of the task about to be executed. Never null.
     */
    void beforeExecute(TaskIdentity<?> taskIdentity);

    /**
     * This method is call immediately after a task has been executed. It is always called, regardless of whether the
     * task completed successfully, or failed with an exception.
     *
     * @param taskIdentity The identity of the task which was executed. Never null.
     * @param state The task state. If the task failed with an exception, the exception is available in this
     * state. Never null.
     */
    void afterExecute(TaskIdentity<?>  taskIdentity, TaskState state);

}