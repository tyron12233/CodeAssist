package com.tyron.builder.internal.serialize;

import java.io.EOFException;

public interface ObjectReader<T> {
    /**
     * Reads the next object from the stream.
     *
     * @throws EOFException When the next object cannot be fully read due to reaching the end of stream.
     */
    T read() throws EOFException, Exception;
}
