package com.tyron.builder.cache;

import com.tyron.builder.internal.Factory;

public interface AsyncCacheAccess {
    /**
     * Submits the given action for execution without waiting for the result.
     *
     * An implementation may execute the action immediately or later. All actions submitted by this method must complete before any action submitted to {@link #read(Factory)} is executed. Actions submitted using this method must run in the order that they are submitted.
     */
    void enqueue(Runnable task);

    /**
     * Runs the given action, blocking until the result is available.
     *
     * All actions submitted using {@link #enqueue(Runnable)} must complete before the action is executed.
     */
    <T> T read(Factory<T> task);

    /**
     * Blocks until all submitted actions have completed. Rethrows any update failure.
     */
    void flush();
}