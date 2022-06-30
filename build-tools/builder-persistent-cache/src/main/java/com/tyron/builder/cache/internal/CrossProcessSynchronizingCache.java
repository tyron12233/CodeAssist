package com.tyron.builder.cache.internal;

import com.tyron.builder.cache.CrossProcessCacheAccess;
import com.tyron.builder.cache.FileLock;
import com.tyron.builder.cache.MultiProcessSafePersistentIndexedCache;

import org.jetbrains.annotations.Nullable;

import java.util.function.Function;

/**
 * Applies cross-process file locking to a backing cache, to ensure that any in-memory and on file state is kept in sync while this process is read from or writing to the cache.
 */
public class CrossProcessSynchronizingCache<K, V> implements MultiProcessSafePersistentIndexedCache<K, V> {
    private final CrossProcessCacheAccess cacheAccess;
    private final MultiProcessSafeAsyncPersistentIndexedCache<K, V> target;

    public CrossProcessSynchronizingCache(MultiProcessSafeAsyncPersistentIndexedCache<K, V> target, CrossProcessCacheAccess cacheAccess) {
        this.target = target;
        this.cacheAccess = cacheAccess;
    }

    @Override
    public String toString() {
        return target.toString();
    }

    @Nullable
    @Override
    public V getIfPresent(final K key) {
        return cacheAccess.withFileLock(() -> target.get(key));
    }

    @Override
    public V get(final K key, final Function<? super K, ? extends V> producer) {
        Runnable runnable = cacheAccess.acquireFileLock();
        return target.get(key, producer, runnable);
    }

    @Override
    public void put(K key, V value) {
        Runnable runnable = cacheAccess.acquireFileLock();
        target.putLater(key, value, runnable);
    }

    @Override
    public void remove(K key) {
        Runnable runnable = cacheAccess.acquireFileLock();
        target.removeLater(key, runnable);
    }

    @Override
    public void afterLockAcquire(FileLock.State currentCacheState) {
        target.afterLockAcquire(currentCacheState);
    }

    @Override
    public void finishWork() {
        target.finishWork();
    }

    @Override
    public void beforeLockRelease(FileLock.State currentCacheState) {
        target.beforeLockRelease(currentCacheState);
    }
}