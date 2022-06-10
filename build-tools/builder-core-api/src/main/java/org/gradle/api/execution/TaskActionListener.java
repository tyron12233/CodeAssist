package org.gradle.api.execution;

import org.gradle.api.Task;
import org.gradle.internal.service.scopes.EventScope;
import org.gradle.internal.service.scopes.Scopes;

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
