package com.tyron.builder.cache.internal;

import com.tyron.builder.internal.serialize.Serializer;

import java.io.File;

/**
 * A factory for caches that contain some calculated value for a particular file. Maintains a cross-build in-memory and persistent cache of computed values. The value for a given file is updated when the content of the file changes.
 */
public interface FileContentCacheFactory {
    /**
     * Creates or locates a cache. The contents of the cache are reused across builds, where possible.
     *
     * @param name An identifier for the cache, used to identify the cache across builds. All instances created using the same identifier will share the same backing store.
     * @param normalizedCacheSize The maximum number of entries to cache in-heap, given a 'typical' heap size. The actual size may vary based on the actual heap available.
     * @param calculator The calculator to use to compute the value for a given file.
     * @param serializer The serializer to use to write values to persistent cache.
     */
    <V> FileContentCache<V> newCache(String name, int normalizedCacheSize, Calculator<? extends V> calculator, Serializer<V> serializer);

    interface Calculator<V> {
        V calculate(File file, boolean isRegularFile);
    }
}
