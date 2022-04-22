package com.tyron.builder.cache;

import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;

public class ManualEvictionInMemoryCache<K, V> implements Cache<K, V> {
    // Use 256 as initial size to start out with enough concurrency.
    private final ConcurrentMap<K, V> map = new ConcurrentHashMap<>(256);

    @Override
    public V get(K key, Function<? super K, ? extends V> factory) {
        return map.computeIfAbsent(key, factory);
    }

    @Override
    public V getIfPresent(K key) {
        return map.get(key);
    }

    @Override
    public void put(K key, V value) {
        map.put(key, value);
    }

    public void retainAll(Collection<? extends K> keysToRetain) {
        map.keySet().retainAll(keysToRetain);
    }

    public void clear() {
        map.clear();
    }
}