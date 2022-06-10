package org.gradle.internal.serialize;

public interface ObjectWriter<T> {
    void write(T value) throws Exception;
}
