package com.tyron.builder.api.internal.tasks;

public class TaskDependencyResolveException extends RuntimeException {
    public TaskDependencyResolveException(String format, Exception e) {
        super(format, e);
    }
}
