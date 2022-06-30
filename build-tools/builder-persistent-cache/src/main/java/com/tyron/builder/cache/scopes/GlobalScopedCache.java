package com.tyron.builder.cache.scopes;

import java.io.File;

/**
 * Factory for creating global caches. These typically live under the ~/.gradle/caches directory.
 */
public interface GlobalScopedCache extends ScopedCache {
    GlobalScopedCache newScopedCache(File rootDir);
}