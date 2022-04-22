package com.tyron.builder.cache.internal.scopes;

import com.tyron.builder.cache.CacheRepository;
import com.tyron.builder.cache.scopes.BuildScopedCache;

import java.io.File;

public class DefaultBuildScopedCache extends AbstractScopedCache implements BuildScopedCache {
    public DefaultBuildScopedCache(File rootDir, CacheRepository cacheRepository) {
        super(rootDir, cacheRepository);
    }
}