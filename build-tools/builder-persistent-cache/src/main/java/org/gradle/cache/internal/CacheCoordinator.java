package org.gradle.cache.internal;

import org.gradle.cache.CacheAccess;
import org.gradle.cache.PersistentIndexedCache;
import org.gradle.cache.PersistentIndexedCacheParameters;

import java.io.Closeable;

public interface CacheCoordinator extends CacheAccess, Closeable {
    void open();

    /**
     * Closes the cache, blocking until all operations have completed.
     */
    @Override
    void close();

    <K, V> PersistentIndexedCache<K, V> newCache(PersistentIndexedCacheParameters<K, V> parameters);

    <K, V> boolean cacheExists(PersistentIndexedCacheParameters<K, V> parameters);
}