package com.tyron.builder.api.execution;

import com.tyron.builder.api.Task;
import com.tyron.builder.internal.service.scopes.EventScope;
import com.tyron.builder.internal.service.scopes.Scopes;

/**
 * <p>A {@code TaskActionListener} is notified of the actions that a task performs.</p>
 */
@EventScope(Scopes.Build.class)
public interface TaskActionListener {
    /**
     * This method is called immediately before the task starts performing its actions.
     *
     * @param task The task which is to perform some actions.
     */
    void beforeActions(Task task);

    /**
     * This method is called immediately after the task has completed performing its actions.
     *
     * @param task The task which has performed some actions.
     */
    void afterActions(Task task);
}
