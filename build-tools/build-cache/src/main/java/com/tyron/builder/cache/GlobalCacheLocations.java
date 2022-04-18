package com.tyron.builder.cache;

/**
 * Identifies if a path is underneath one of Gradle's global caches.
 *
 * We expect only Gradle itself to change things in the global caches directories.
 *
 * The quasi-immutability of global caches allows for some optimizations by retaining file system state in-memory.
 */
public interface GlobalCacheLocations {

    /**
     * Checks if a given path is inside one of Gradle's global caches.
     */
    boolean isInsideGlobalCache(String path);
}