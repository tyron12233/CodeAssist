package org.gradle.cache.internal;


public interface InMemoryCacheController {
    String getCacheId();
    void clearInMemoryCache();
}