package com.tyron.builder.internal.serialize;

import java.io.EOFException;

public interface Serializer<T> {
    /**
     * Reads the next object from the given stream. The implementation must not perform any buffering, so that it reads only those bytes from the input stream that are
     * required to deserialize the next object.
     *
     * @throws EOFException When the next object cannot be fully read due to reaching the end of stream.
     */
    T read(Decoder decoder) throws EOFException, Exception;

    /**
     * Writes the given object to the given stream. The implementation must not perform any buffering.
     */
    void write(Encoder encoder, T value) throws Exception;
}