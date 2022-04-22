package com.tyron.builder.api.internal.tasks;

import com.tyron.builder.api.Action;
import com.tyron.builder.api.Task;

public interface TaskDependencyResolveContext extends Action<Task> {
    @Override
    default void execute(Task task) {
        add(task);
    }

    /**
     * Adds an object that can contribute tasks to the result
     */
    void add(Object dependency);

    /**
     * Visits a failure to visit the dependencies of an object.
     */
    void visitFailure(Throwable failure);

    /**
     * Returns the task whose dependencies are being visited.
     */
    Task getTask();
}