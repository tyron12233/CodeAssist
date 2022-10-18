package org.gradle.cache.internal;

import org.gradle.cache.CacheDecorator;

public interface InMemoryCacheDecoratorFactory {
    CacheDecorator decorator(int maxEntriesToKeepInMemory, boolean cacheInMemoryForShortLivedProcesses);
}