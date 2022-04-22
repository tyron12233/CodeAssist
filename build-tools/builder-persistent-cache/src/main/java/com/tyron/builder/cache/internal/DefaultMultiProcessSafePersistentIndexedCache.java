package com.tyron.builder.cache.internal;

import com.tyron.builder.internal.Factory;
import com.tyron.builder.cache.FileAccess;
import com.tyron.builder.cache.FileIntegrityViolationException;
import com.tyron.builder.cache.FileLock;
import com.tyron.builder.cache.MultiProcessSafePersistentIndexedCache;
import com.tyron.builder.cache.internal.btree.BTreePersistentIndexedCache;

import java.util.function.Function;

public class DefaultMultiProcessSafePersistentIndexedCache<K, V> implements MultiProcessSafePersistentIndexedCache<K, V> {
    private final FileAccess fileAccess;
    private final Factory<BTreePersistentIndexedCache<K, V>> factory;
    private BTreePersistentIndexedCache<K, V> cache;

    public DefaultMultiProcessSafePersistentIndexedCache(Factory<BTreePersistentIndexedCache<K, V>> factory, FileAccess fileAccess) {
        this.factory = factory;
        this.fileAccess = fileAccess;
    }

    @Override
    public String toString() {
        return fileAccess.toString();
    }

    @Override
    public V getIfPresent(final K key) {
        final BTreePersistentIndexedCache<K, V> cache = getCache();
        try {
            return fileAccess.readFile((Factory<V>) () -> cache.get(key));
        } catch (FileIntegrityViolationException e) {
            return null;
        }
    }

    @Override
    public V get(K key, Function<? super K, ? extends V> producer) {
        V value = getIfPresent(key);
        if (value == null) {
            value = producer.apply(key);
            put(key, value);
        }
        return value;
    }

    @Override
    public void put(final K key, final V value) {
        final BTreePersistentIndexedCache<K, V> cache = getCache();
        // Use writeFile because the cache can internally recover from datafile
        // corruption, so we don't care at this level if it's corrupt
        fileAccess.writeFile(() -> cache.put(key, value));
    }

    @Override
    public void remove(final K key) {
        final BTreePersistentIndexedCache<K, V> cache = getCache();
        // Use writeFile because the cache can internally recover from datafile
        // corruption, so we don't care at this level if it's corrupt
        fileAccess.writeFile(() -> cache.remove(key));
    }

    @Override
    public void afterLockAcquire(FileLock.State currentCacheState) {
    }

    @Override
    public void finishWork() {
        if (cache != null) {
            try {
                fileAccess.writeFile(() -> cache.close());
            } finally {
                cache = null;
            }
        }
    }

    @Override
    public void beforeLockRelease(FileLock.State currentCacheState) {
    }

    private BTreePersistentIndexedCache<K, V> getCache() {
        if (cache == null) {
            // Use writeFile because the cache can internally recover from datafile
            // corruption, so we don't care at this level if it's corrupt
            fileAccess.writeFile(() -> cache = factory.create());
        }
        return cache;
    }
}
