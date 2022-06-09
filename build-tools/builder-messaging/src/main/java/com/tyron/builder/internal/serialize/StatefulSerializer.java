package com.tyron.builder.internal.serialize;

/**
 * Implementations must allow concurrent reading and writing, so that a thread can read and a thread can write at the same time.
 * Implementations do not need to support multiple read threads or multiple write threads.
 */
public interface StatefulSerializer<T> {
    /**
     * Should not perform any buffering
     */
    ObjectReader<T> newReader(Decoder decoder);

    /**
     * Should not perform any buffering
     */
    ObjectWriter<T> newWriter(Encoder encoder);
}
