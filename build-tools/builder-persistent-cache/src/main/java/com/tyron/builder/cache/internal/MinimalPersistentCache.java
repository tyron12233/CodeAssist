package com.tyron.builder.cache.internal;

import com.tyron.builder.cache.Cache;
import com.tyron.builder.cache.PersistentIndexedCache;

import java.util.function.Function;

public class MinimalPersistentCache<K, V> implements Cache<K, V> {
    private final PersistentIndexedCache<K, V> cache;

    public MinimalPersistentCache(PersistentIndexedCache<K, V> cache) {
        this.cache = cache;
    }

    @Override
    public V get(K key, Function<? super K, ? extends V> factory) {
        V cached = cache.getIfPresent(key);
        if (cached != null) {
            return cached;
        }

        V value = factory.apply(key); //don't synchronize value creation
        //we could potentially avoid creating value that is already being created by a different thread.

        cache.put(key, value);
        return value;
    }

    @Override
    public V getIfPresent(K value) {
        return cache.getIfPresent(value);
    }

    @Override
    public void put(K key, V value) {
        cache.put(key, value);
    }
}

