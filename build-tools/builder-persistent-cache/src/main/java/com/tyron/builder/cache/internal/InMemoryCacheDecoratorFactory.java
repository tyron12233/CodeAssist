package com.tyron.builder.cache.internal;

import com.tyron.builder.cache.CacheDecorator;

public interface InMemoryCacheDecoratorFactory {
    CacheDecorator decorator(int maxEntriesToKeepInMemory, boolean cacheInMemoryForShortLivedProcesses);
}