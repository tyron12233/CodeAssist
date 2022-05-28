package com.tyron.builder.internal.model;

import com.tyron.builder.internal.Try;

import javax.annotation.concurrent.ThreadSafe;

/**
 * Represents a calculated immutable value that is calculated once and then consumed by multiple threads.
 */
@ThreadSafe
public interface CalculatedValue<T> {
    /**
     * Returns the value, failing if it has not been calculated.
     * Does not calculate the value on demand and does not block if the value is currently being calculated.
     *
     * <p>Rethrows any exception that happened while calculating the value</p>
     */
    T get() throws IllegalStateException;

    /**
     * Returns the value, or null if it has not been calculated.
     * Does not calculate the value on demand and does not block if the value is currently being calculated.
     *
     * <p>Rethrows any exception that happened while calculating the value</p>
     */
    T getOrNull();

    /**
     * Returns the result of calculating the value, failing if it has not been calculated.
     * Does not calculate the value on demand and does not block if the value is currently being calculated.
     */
    Try<T> getValue() throws IllegalStateException;

    /**
     * Returns true if this value is already calculated. Note that some other thread may currently be calculating the value.
     */
    boolean isFinalized();

    /**
     * Calculates the value, if not already calculated. Collects any exception and does not rethrow them.
     * Blocks until the value is finalized, either by this thread or some other thread.
     */
    void finalizeIfNotAlready();

    /**
     * Returns the resource that will be required to calculate this value.
     */
    ModelContainer<?> getResourceToLock();
}
