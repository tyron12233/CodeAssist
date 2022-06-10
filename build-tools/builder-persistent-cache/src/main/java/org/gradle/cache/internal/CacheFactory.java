package org.gradle.cache.internal;

import org.gradle.api.Action;
import org.gradle.cache.CacheBuilder;
import org.gradle.cache.CacheOpenException;
import org.gradle.cache.CleanupAction;
import org.gradle.cache.LockOptions;
import org.gradle.cache.PersistentCache;

import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.Map;

public interface CacheFactory {
    /**
     * Opens a cache with the given options. The caller must close the cache when finished with it.
     */
    PersistentCache open(File cacheDir, String displayName, Map<String, ?> properties, CacheBuilder.LockTarget lockTarget, LockOptions lockOptions, @Nullable Action<? super PersistentCache> initializer, @Nullable CleanupAction cleanup) throws CacheOpenException;
}