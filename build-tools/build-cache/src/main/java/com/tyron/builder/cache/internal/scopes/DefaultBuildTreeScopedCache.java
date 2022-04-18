package com.tyron.builder.cache.internal.scopes;

import com.tyron.builder.cache.CacheRepository;
import com.tyron.builder.cache.scopes.BuildTreeScopedCache;

import java.io.File;

public class DefaultBuildTreeScopedCache extends AbstractScopedCache implements BuildTreeScopedCache {
    public DefaultBuildTreeScopedCache(File rootDir, CacheRepository cacheRepository) {
        super(rootDir, cacheRepository);
    }
}