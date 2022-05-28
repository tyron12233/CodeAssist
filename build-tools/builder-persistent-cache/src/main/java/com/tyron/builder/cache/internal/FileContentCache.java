package com.tyron.builder.cache.internal;

import javax.annotation.concurrent.ThreadSafe;

import java.io.File;

/**
 * A cache with keys that represent files, and whose values are computed from the contents of the file.
 */
@ThreadSafe
public interface FileContentCache<V> {
    /**
     * Returns the computed value for the given file.
     */
    V get(File file);
}
