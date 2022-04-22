package com.tyron.builder.cache;

public interface CacheDecorator {
    /**
     * @param cacheId Unique id for this cache instance.
     * @param cacheName Name for the type of contents stored in this cache instance.
     */
    <K, V> MultiProcessSafePersistentIndexedCache<K, V> decorate(String cacheId, String cacheName, MultiProcessSafePersistentIndexedCache<K, V> persistentCache, CrossProcessCacheAccess crossProcessCacheAccess, AsyncCacheAccess asyncCacheAccess);
}