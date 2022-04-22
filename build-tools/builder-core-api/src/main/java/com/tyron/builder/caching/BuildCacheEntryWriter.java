package com.tyron.builder.caching;

import java.io.IOException;
import java.io.OutputStream;

/**
 * Writer to serialize a build cache entry.
 *
 * @since 3.3
 */
public interface BuildCacheEntryWriter {
    /**
     * Writes a build cache entry to the given stream.
     * <p>
     * The given output stream will be closed by this method.
     *
     * @param output output stream to write build cache entry to
     * @throws IOException when an I/O error occurs when writing the cache entry to the given output stream
     */
    void writeTo(OutputStream output) throws IOException;

    /**
     * Returns the size of the build cache entry to be written.
     *
     * @return the size of the build cache entry to be written.
     * @since 4.1
     */
    long getSize();
}