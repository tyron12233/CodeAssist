package com.tyron.builder.cache;

import java.io.File;
import java.util.List;

/**
 * Represents a location for global Gradle caches.
 *
 * The global cache is managed by Gradle, so we Gradle needs to take care
 * of informing all the infrastructure about changes to it.
 */
public interface GlobalCache {
    /**
     * Returns the root directories of the global cache.
     */
    List<File> getGlobalCacheRoots();
}