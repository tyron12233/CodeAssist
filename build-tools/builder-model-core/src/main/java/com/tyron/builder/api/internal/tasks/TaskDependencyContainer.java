package com.tyron.builder.api.internal.tasks;

/**
 * An object that has task dependencies associated with it.
 */
public interface TaskDependencyContainer {
    TaskDependencyContainer EMPTY = context -> {
    };

    /**
     * Adds the dependencies from this container to the given context. Failures to calculate the build dependencies are supplied to the context.
     */
    void visitDependencies(TaskDependencyResolveContext context);
}