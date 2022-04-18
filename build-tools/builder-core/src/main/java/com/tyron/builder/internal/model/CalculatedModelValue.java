package com.tyron.builder.internal.model;

import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;
import java.util.function.Function;

/**
 * Represents a value that is calculated from some mutable state managed by a {@link ModelContainer}, where the calculated value may
 * be used by multiple threads.
 */
@ThreadSafe
public interface CalculatedModelValue<T> {
    /**
     * Returns the current value, failing if not present.
     *
     * <p>May be called by any thread. This method returns immediately and does not block to wait for any currently running or pending calls to {@link #update(Function)} to complete.
     */
    T get() throws IllegalStateException;

    /**
     * Returns the current value, returning {@code null} if not present.
     *
     * <p>May be called by any thread. This method returns immediately and does not block to wait for any currently running or pending calls to {@link #update(Function)} to complete.
     */
    @Nullable
    T getOrNull();

    /**
     * Updates the current value. The function is passed the current value or {@code null} if there is no value, and the function's return value is
     * used as the new value.
     *
     * <p>The calling thread must own the mutable state from which the value is calculated (via {@link ModelContainer#fromMutableState(Function)}). At most a single thread
     * will run the update function at a given time. This additional guarantee is because the mutable state lock may be released
     * while the function is running or while waiting to access the value.
     */
    T update(Function<T, T> updateFunction);

    /**
     * Sets the current value.
     *
     * <p>The calling thread must own the mutable state from which the value is calculated.</p>
     */
    void set(T newValue);
}
