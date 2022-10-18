package com.tyron.builder.utils;

import com.android.annotations.NonNull;

/** Function that can throw an {@link Exception}. */
@FunctionalInterface
public interface ExceptionFunction<T, R> {

    /**
     * Performs an operation on the given input.
     *
     * @param input the input
     * @return the result of the operation
     */
    R accept(@NonNull T input) throws Exception;
}