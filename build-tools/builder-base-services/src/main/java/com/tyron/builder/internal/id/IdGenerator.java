package com.tyron.builder.internal.id;

/**
 * Generates a sequence of unique ids of type T. Implementations must be thread-safe.
 */
public interface IdGenerator<T> {
    /**
     * Generates a new id. Values must be serializable.
     *
     * @return The id. Must not return null. Must not return a given value more than once.
     */
    T generateId();
}