package com.tyron.builder.api.internal.tasks;

public abstract class AbstractTaskDependencyResolveContext implements TaskDependencyResolveContext {
    @Override
    public void visitFailure(Throwable failure) {
        // Rethrow
        throw new RuntimeException(failure);
    }
}
