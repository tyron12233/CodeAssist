package org.gradle.cache.internal;

import org.gradle.cache.Cache;
import org.gradle.internal.concurrent.Synchronizer;

import java.util.function.Function;

public class CacheAccessSerializer<K, V> implements Cache<K, V> {

    final private Synchronizer synchronizer = new Synchronizer();
    final private Cache<K, V> cache;

    public CacheAccessSerializer(Cache<K, V> cache) {
        this.cache = cache;
    }

    @Override
    public V get(final K key, final Function<? super K, ? extends V> factory) {
        return synchronizer.synchronize(() -> cache.get(key, factory));
    }

    @Override
    public V getIfPresent(K key) {
        return synchronizer.synchronize(() -> cache.getIfPresent(key));
    }

    @Override
    public void put(K key, V value) {
        synchronizer.synchronize(() -> cache.put(key, value));
    }
}
