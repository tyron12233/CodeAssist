package com.tyron.builder.api;

/**
 * Performs some actions against objects of of type T
 *
 * @param <T> the type of object which this action accepts
 */
public interface Action<T> {

    /**
     * Performs this action against the given object
     *
     * @param t The object to perform the action on.
     */
    void execute(T t);
}
