package com.tyron.builder.caching;

import java.io.IOException;
import java.io.InputStream;

/**
 * A reader for build cache entries.
 *
 * @since 3.3
 */
public interface BuildCacheEntryReader {
    /**
     * Read a build cache entry from the given input stream.
     * <p>
     * The given input stream will be closed by this method.
     *
     * @param input input stream that contains the build cache entry
     * @throws IOException when an I/O error occurs when reading the cache entry from the given input stream
     */
    void readFrom(InputStream input) throws IOException;
}