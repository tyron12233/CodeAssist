package com.tyron.builder.cache.scopes;

import com.tyron.builder.cache.CacheBuilder;
import com.tyron.builder.cache.CacheRepository;

import java.io.File;

public interface ScopedCache {
    /**
     * Creates a Gradle-version specific cache in this scope. See {@link CacheRepository#cache(String)}.
     *
     * @param key A unique name for the cache.
     */
    CacheBuilder cache(String key);

    /**
     * Creates a cross Gradle version cache in this scope. See {@link CacheRepository#cache(String)}.
     *
     * @param key A unique name for the cache.
     */
    CacheBuilder crossVersionCache(String key);

    /**
     * Returns the root directory of this cache. You should avoid using this method and instead use one of the other methods.
     */
    File getRootDir();

    /**
     * Returns the base directory that would be used for a Gradle-version specific cache om this scope.
     */
    File baseDirForCache(String key);

    /**
     * Returns the base directory that would be used for a cross Gradle version specific cache om this scope.
     */
    File baseDirForCrossVersionCache(String key);
}