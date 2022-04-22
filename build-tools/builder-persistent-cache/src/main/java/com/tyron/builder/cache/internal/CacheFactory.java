package com.tyron.builder.cache.internal;

import com.tyron.builder.api.Action;
import com.tyron.builder.cache.CacheBuilder;
import com.tyron.builder.cache.CacheOpenException;
import com.tyron.builder.cache.CleanupAction;
import com.tyron.builder.cache.LockOptions;
import com.tyron.builder.cache.PersistentCache;

import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.Map;

public interface CacheFactory {
    /**
     * Opens a cache with the given options. The caller must close the cache when finished with it.
     */
    PersistentCache open(File cacheDir, String displayName, Map<String, ?> properties, CacheBuilder.LockTarget lockTarget, LockOptions lockOptions, @Nullable Action<? super PersistentCache> initializer, @Nullable CleanupAction cleanup) throws CacheOpenException;
}