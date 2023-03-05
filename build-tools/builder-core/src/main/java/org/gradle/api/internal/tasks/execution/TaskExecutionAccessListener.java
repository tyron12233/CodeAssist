package org.gradle.api.internal.tasks.execution;

import org.gradle.api.internal.TaskInternal;
import org.gradle.internal.service.scopes.EventScope;
import org.gradle.internal.service.scopes.Scopes;


@EventScope(Scopes.Build.class)
public interface TaskExecutionAccessListener {

    /**
     * Called when accessing the project during task execution.
     */
    void onProjectAccess(String invocationDescription, TaskInternal task);

    /**
     * Called when accessing task dependencies during task execution.
     */
    void onTaskDependenciesAccess(String invocationDescription, TaskInternal task);
}