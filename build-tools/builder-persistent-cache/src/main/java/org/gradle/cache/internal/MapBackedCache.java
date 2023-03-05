package org.gradle.cache.internal;

import java.util.Map;

public class MapBackedCache<K, V> extends CacheSupport<K, V> {

    private final Map<K, V> map;

    public MapBackedCache(Map<K, V> map) {
        this.map = map;
    }

    @Override
    protected <T extends K> V doGet(T key) {
        return map.get(key);
    }

    @Override
    protected <T extends K, N extends V> void doCache(T key, N value) {
        map.put(key, value);
    }

}
