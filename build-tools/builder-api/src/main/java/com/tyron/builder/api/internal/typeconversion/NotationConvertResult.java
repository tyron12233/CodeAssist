package com.tyron.builder.api.internal.typeconversion;

public interface NotationConvertResult<T> {
    boolean hasResult();

    /**
     * Invoked when a {@link NotationConverter} is able to convert a notation to a result.
     */
    void converted(T result);
}