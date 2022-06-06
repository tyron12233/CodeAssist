package com.tyron.builder.internal.work;

/**
 * Provides new {@link ConditionalExecutionQueue} objects
 */
public interface ConditionalExecutionQueueFactory {
    /**
     * Provides a {@link ConditionalExecutionQueue} that can process {@link ConditionalExecution} objects that
     * return the provided result class.
     */
    <T> ConditionalExecutionQueue<T> create(String displayName, Class<T> resultClass);
}
