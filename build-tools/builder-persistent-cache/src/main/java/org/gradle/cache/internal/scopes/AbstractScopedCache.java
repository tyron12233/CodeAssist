package org.gradle.cache.internal.scopes;

import org.gradle.cache.CacheBuilder;
import org.gradle.cache.CacheRepository;
import org.gradle.cache.internal.CacheScopeMapping;
import org.gradle.cache.internal.VersionStrategy;
import org.gradle.cache.scopes.ScopedCache;
import org.gradle.util.GradleVersion;

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