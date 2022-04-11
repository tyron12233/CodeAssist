package com.tyron.builder.api.work;

/**
 * Represents the completion of an item of asynchronous work
 */
public interface AsyncWorkCompletion {
    /**
     * Block until the work item has completed.
     */
    void waitForCompletion();

    /**
     * Returns true if the work item is completed.
     */
    boolean isComplete();

    /**
     * Cancels this work item.
     */
    void cancel();
}