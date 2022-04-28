package com.tyron.builder.project.cache;

import com.tyron.common.util.Cache;

public interface CacheHolder {

    class CacheKey<K, V> {

        private final String name;

        public CacheKey(String name) {
            this.name = name;
        }

        public String getName() {
            return name;
        }
    }

    <K, V> Cache<K, V> getCache(CacheKey<K, V> key, Cache<K, V> defaultValue);

    <K, V> void put(CacheKey<K, V> key, Cache<K, V> value);
}
