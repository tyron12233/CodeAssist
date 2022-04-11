package com.tyron.builder.cache;

import java.io.File;

/**
 * A repository of persistent caches. A cache is a store of persistent data backed by a directory.
 *
 * This is migrating to become an internal type for the caching infrastructure. Please use
 * {@link GlobalScopedCache}
 * or {@link BuildTreeScopedCache}
 * or {@link BuildScopedCache} instead.
 */
public interface CacheRepository {
    /**
     * Returns a builder for the cache with the given key and global scope. Default is a Gradle version-specific cache shared by all builds, though this
     * can be changed using the provided builder.
     *
     * <p>By default a cache is opened with a shared lock, so that it can be accessed by multiple processes. It is the caller's responsibility
     * to coordinate access to the cache. The initial lock level can be changed using the provided builder </p>
     *
     * @param key The cache key. This is a unique identifier within the cache scope.
     * @return The builder.
     */
    CacheBuilder cache(String key);

    /**
     * Returns a builder for the cache with the given base directory. You should prefer one of the other methods over using this method.
     *
     * <p>By default a cache is opened with a shared lock, so that it can be accessed by multiple processes. It is the caller's responsibility
     * to coordinate access to the cache. The initial lock level can be changed using the provided builder </p>
     */
    CacheBuilder cache(File baseDir);
}