package com.tyron.builder.cache;


import com.tyron.builder.internal.serialize.Serializer;

import java.io.Closeable;
import java.io.File;

/**
 * Represents a directory that can be used for caching.
 *
 * <p>By default, a shared lock is held on this cache by this process, to prevent it being removed or rebuilt by another process
 * while it is in use. You can change this use {@link CacheBuilder#withLockOptions(LockOptions)}.
 *
 * <p>You can use {@link CacheBuilder#withInitializer(org.gradle.api.Action)} to provide an action to initialize the contents
 * of the cache, for building a read-only cache. An exclusive lock is held by this process while the initializer is running.</p>
 *
 * <p>You can also use {@link #useCache(org.gradle.internal.Factory)} to perform some action on the cache while holding an exclusive
 * lock on the cache.
 * </p>
 */
public interface PersistentCache extends CacheAccess, Closeable, CleanableStore {
    /**
     * Returns the base directory for this cache.
     */
    @Override
    File getBaseDir();

    /**
     * Creates an indexed cache implementation that is contained within this cache. This method may be used at any time.
     *
     * <p>The returned cache may only be used by an action being run from {@link #useCache(org.gradle.internal.Factory)}.
     * In this instance, an exclusive lock will be held on the cache.
     *
     */
    <K, V> PersistentIndexedCache<K, V> createCache(PersistentIndexedCacheParameters<K, V> parameters);

    /**
     * Creates an indexed cache implementation that is contained within this store. This method may be used at any time.
     *
     * <p>The returned cache may only be used by an action being run from {@link #useCache(org.gradle.internal.Factory)}.
     * In this instance, an exclusive lock will be held on the cache.
     *
     */
    <K, V> PersistentIndexedCache<K, V> createCache(String name, Class<K> keyType, Serializer<V> valueSerializer);

    <K, V> boolean cacheExists(PersistentIndexedCacheParameters<K, V> parameters);

    /**
     * Closes this cache, blocking until all operations are complete.
     */
    @Override
    void close();
}