package com.tyron.builder.caching.internal.controller.service;

import com.google.common.annotations.VisibleForTesting;
import com.tyron.builder.caching.BuildCacheKey;
import com.tyron.builder.caching.BuildCacheService;

import org.jetbrains.annotations.Nullable;

import java.io.Closeable;
import java.io.File;
import java.util.Optional;
import java.util.function.Function;

public interface RemoteBuildCacheServiceHandle extends Closeable {

    @Nullable
    @VisibleForTesting
    BuildCacheService getService();

    boolean canLoad();

    /**
     * Load the cached entry corresponding to the given cache key to the given target file.
     *
     * If canLoad() returns false, then this method will do nothing and will return empty result.
     */
    Optional<BuildCacheLoadResult> maybeLoad(BuildCacheKey key, File toFile, Function<File, BuildCacheLoadResult> unpackFunction);

    boolean canStore();

    /**
     * Stores the file to the cache.
     *
     * If canStore() returns false, then this method will do nothing and will return false.
     *
     * Returns true if store was completed.
     */
    boolean maybeStore(BuildCacheKey key, File file);

    @Override
    void close();
}