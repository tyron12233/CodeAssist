package org.gradle.api.internal.tasks;

import org.gradle.api.Action;
import org.gradle.api.Task;
import org.gradle.api.internal.artifacts.transform.TransformationDependency;
import org.jetbrains.annotations.Nullable;

public interface TaskDependencyResolveContext extends Action<Task> {
    @Override
    default void execute(Task task) {
        add(task);
    }

    /**
     * Adds an object that can contribute tasks to the result. Supported types:
     *
     * <ul>
     *
     * <li>{@link org.gradle.api.Task}</li>
     *
     * <li>{@link org.gradle.api.tasks.TaskDependency}</li>
     *
     * <li>{@link org.gradle.api.internal.tasks.TaskDependencyContainer}</li>
     *
     * <li>{@link org.gradle.api.Buildable}</li>
     *
     * <li>{@link TransformationDependency}</li>
     *
     * <li>{@link FinalizeAction}</li>
     *
     * <li>{@link WorkNodeAction}</li>
     *
     * </ul>
     */
    void add(Object dependency);

    /**
     * Visits a failure to visit the dependencies of an object.
     */
    void visitFailure(Throwable failure);

    /**
     * Returns the task whose dependencies are being visited.
     */
    @Nullable
    Task getTask();
}