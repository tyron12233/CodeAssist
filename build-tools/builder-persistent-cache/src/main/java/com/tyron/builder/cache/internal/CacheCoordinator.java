package com.tyron.builder.cache.internal;

import com.tyron.builder.cache.CacheAccess;
import com.tyron.builder.cache.PersistentIndexedCache;
import com.tyron.builder.cache.PersistentIndexedCacheParameters;

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