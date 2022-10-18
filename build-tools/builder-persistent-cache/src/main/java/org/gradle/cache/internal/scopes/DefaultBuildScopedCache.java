package org.gradle.cache.internal.scopes;

import org.gradle.cache.CacheRepository;
import org.gradle.cache.scopes.BuildScopedCache;

import java.io.File;

public class DefaultBuildScopedCache extends AbstractScopedCache implements BuildScopedCache {
    public DefaultBuildScopedCache(File rootDir, CacheRepository cacheRepository) {
        super(rootDir, cacheRepository);
    }
}