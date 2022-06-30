package com.tyron.builder.cache.internal.scopes;

import com.tyron.builder.cache.CacheBuilder;
import com.tyron.builder.cache.CacheRepository;
import com.tyron.builder.cache.internal.CacheScopeMapping;
import com.tyron.builder.cache.internal.VersionStrategy;
import com.tyron.builder.cache.scopes.ScopedCache;
import com.tyron.builder.util.GradleVersion;

import java.io.File;

public abstract class AbstractScopedCache implements ScopedCache {
    private final CacheScopeMapping cacheScopeMapping;
    private final CacheRepository cacheRepository;
    private final File rootDir;

    public AbstractScopedCache(File rootDir, CacheRepository cacheRepository) {
        this.rootDir = rootDir;
        this.cacheScopeMapping = new DefaultCacheScopeMapping(rootDir, GradleVersion.current());
        this.cacheRepository = cacheRepository;
    }

    protected CacheRepository getCacheRepository() {
        return cacheRepository;
    }

    @Override
    public File getRootDir() {
        return rootDir;
    }

    @Override
    public CacheBuilder cache(String key) {
        return cacheRepository.cache(baseDirForCache(key));
    }

    @Override
    public CacheBuilder crossVersionCache(String key) {
        return cacheRepository.cache(baseDirForCrossVersionCache(key));
    }

    @Override
    public File baseDirForCache(String key) {
        return cacheScopeMapping.getBaseDirectory(rootDir, key, VersionStrategy.CachePerVersion);
    }

    @Override
    public File baseDirForCrossVersionCache(String key) {
        return cacheScopeMapping.getBaseDirectory(rootDir, key, VersionStrategy.SharedCache);
    }
}