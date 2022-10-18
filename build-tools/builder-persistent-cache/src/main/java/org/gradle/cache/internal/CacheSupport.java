package org.gradle.cache.internal;

import org.gradle.cache.Cache;

import java.util.function.Function;

public abstract class CacheSupport<K, V> implements Cache<K, V> {

    @Override
    public V get(K key, Function<? super K, ? extends V> factory) {
        V value = doGet(key);
        if (value == null) {
            value = factory.apply(key);
            doCache(key, value);
        }

        return value;
    }

    @Override
    public V getIfPresent(K key) {
        return doGet(key);
    }

    @Override
    public void put(K key, V value) {
        doCache(key, value);
    }

    abstract protected <T extends K> V doGet(T key);

    abstract protected <T extends K, N extends V> void doCache(T key, N value);
}
