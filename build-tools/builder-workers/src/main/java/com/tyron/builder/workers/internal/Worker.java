package com.tyron.builder.workers.internal;

/**
 * A service that executes work locally.
 */
public interface Worker {
    DefaultWorkResult execute(SimpleActionExecutionSpec<?> spec);
}
