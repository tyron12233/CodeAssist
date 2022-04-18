package com.tyron.builder.internal.serialize;

import java.io.Flushable;
import java.io.IOException;

/**
 * Represents an {@link Encoder} that buffers encoded data prior to writing to the backing stream.
 */
public interface FlushableEncoder extends Encoder, Flushable {
    /**
     * Ensures that all buffered data has been written to the backing stream. Does not flush the backing stream.
     */
    @Override
    void flush() throws IOException;
}