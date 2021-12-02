package com.tyron.builder.compiler2.api;

/**
 * Performs some action against objects of type T.
 *
 * @param <T> The type of object which this action accepts.
 */
public interface Action<T> {
    /**
     * Performs this action against the given object.
     *
     * @param t The object to perform the action on.
     */
    void execute(T t);
}