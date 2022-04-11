package com.tyron.builder.api.internal.concurrent;

public interface ExecutorFactory {
    /**
     * Creates an executor which can run multiple actions concurrently. It is the caller's responsibility to stop the executor.
     *
     * The executor will collect failures thrown by actions and rethrow when the executor is stopped.
     *
     * @param displayName The display name for the this executor. Used for thread names, logging and error message.
     * @return The executor.
     */
    ManagedExecutor create(String displayName);

    /**
     * Creates an executor which can run multiple tasks concurrently. It is the caller's responsibility to stop the executor.
     *
     * The executor will collect failures thrown by actions and rethrow when the executor is stopped.
     *
     * @param displayName The display name for the this executor. Used for thread names, logging and error message.
     * @param fixedSize The maximum number of threads allowed
     * @return The executor.
     */
    ManagedExecutor create(String displayName, int fixedSize);

    /**
     * Creates a scheduled executor which can run tasks periodically. It is the caller's responsibility to stop the executor.
     *
     * The created scheduled executor has a fixed pool size of {@literal fixedSize}.
     *
     * The executor will collect failures thrown by actions and rethrow when the executor is stopped.
     *
     * @param displayName The display name for the this executor. Used for thread names, logging and error message.
     * @param fixedSize The maximum number of threads allowed
     * @return The executor
     * @see java.util.concurrent.ScheduledExecutorService
     */
    ManagedScheduledExecutor createScheduled(String displayName, int fixedSize);
}