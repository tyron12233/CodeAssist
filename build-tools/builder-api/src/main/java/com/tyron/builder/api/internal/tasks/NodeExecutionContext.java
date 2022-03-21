package com.tyron.builder.api.internal.tasks;

public interface NodeExecutionContext {
    /**
     * Locates the given execution service.
     */
    <T> T getService(Class<T> type);
}