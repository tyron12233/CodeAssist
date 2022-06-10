package org.gradle.cache.internal.scopes;

import org.gradle.cache.CacheRepository;
import org.gradle.cache.scopes.BuildTreeScopedCache;

import java.io.File;

public class DefaultBuildTreeScopedCache extends AbstractScopedCache implements BuildTreeScopedCache {
    public DefaultBuildTreeScopedCache(File rootDir, CacheRepository cacheRepository) {
        super(rootDir, cacheRepository);
    }
}