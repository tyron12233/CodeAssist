package com.tyron.builder.internal.serialize;

public interface ObjectWriter<T> {
    void write(T value) throws Exception;
}
