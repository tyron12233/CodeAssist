package com.tyron.builder.cache.internal;

import com.tyron.builder.cache.AsyncCacheAccess;
import com.tyron.builder.cache.FileLock;
import com.tyron.builder.cache.MultiProcessSafePersistentIndexedCache;

import org.jetbrains.annotations.Nullable;

import java.util.function.Function;

public class AsyncCacheAccessDecoratedCache<K, V> implements MultiProcessSafeAsyncPersistentIndexedCache<K, V> {
    private final AsyncCacheAccess asyncCacheAccess;
    private final MultiProcessSafePersistentIndexedCache<K, V> persistentCache;

    public AsyncCacheAccessDecoratedCache(AsyncCacheAccess asyncCacheAccess, MultiProcessSafePersistentIndexedCache<K, V> persistentCache) {
        this.asyncCacheAccess = asyncCacheAccess;
        this.persistentCache = persistentCache;
    }

    @Override
    public String toString() {
        return "{async-cache cache: " + persistentCache + "}";
    }

    @Nullable
    @Override
    public V get(final K key) {
        return asyncCacheAccess.read(() -> persistentCache.getIfPresent(key));
    }

    @Override
    public V get(K key, Function<? super K, ? extends V> producer, Runnable completion) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void putLater(final K key, final V value, final Runnable completion) {
        try {
            asyncCacheAccess.enqueue(() -> {
                try {
                    persistentCache.put(key, value);
                } finally {
                    completion.run();
                }
            });
        } catch (RuntimeException e) {
            completion.run();
            throw e;
        }
    }

    @Override
    public void removeLater(final K key, final Runnable completion) {
        try {
            asyncCacheAccess.enqueue(() -> {
                try {
                    persistentCache.remove(key);
                } finally {
                    completion.run();
                }
            });
        } catch (RuntimeException e) {
            completion.run();
            throw e;
        }
    }

    @Override
    public void afterLockAcquire(FileLock.State currentCacheState) {
        persistentCache.afterLockAcquire(currentCacheState);
    }

    @Override
    public void finishWork() {
        persistentCache.finishWork();
    }

    @Override
    public void beforeLockRelease(FileLock.State currentCacheState) {
        persistentCache.beforeLockRelease(currentCacheState);
    }
}