package com.tyron.builder.cache.internal.scopes;


import com.tyron.builder.cache.CacheRepository;
import com.tyron.builder.cache.GlobalCache;
import com.tyron.builder.cache.scopes.GlobalScopedCache;

import java.io.File;
import java.util.Collections;
import java.util.List;

public class DefaultGlobalScopedCache extends AbstractScopedCache implements GlobalScopedCache, GlobalCache {
    public DefaultGlobalScopedCache(File rootDir, CacheRepository cacheRepository) {
        super(rootDir, cacheRepository);
    }

    @Override
    public GlobalScopedCache newScopedCache(File rootDir) {
        return new DefaultGlobalScopedCache(rootDir, getCacheRepository());
    }

    @Override
    public List<File> getGlobalCacheRoots() {
        return Collections.singletonList(getRootDir());
    }
}