package com.tyron.builder.internal.work;

/**
 * Represents an execution.
 */
public interface ConditionalExecution<T> {
    /**
     * Provides the Runnable that should be executed once the resource lock is acquired.
     */
    Runnable getExecution();

    /**
     * Blocks waiting for this execution to complete. Returns a result provided by the execution.
     */
    T await();

    /**
     * This method will be called upon completion of the execution.
     */
    void complete();

    /**
     * Whether this execution has been completed or not.
     */
    boolean isComplete();

    /**
     * Cancels this execution.
     */
    void cancel();
}