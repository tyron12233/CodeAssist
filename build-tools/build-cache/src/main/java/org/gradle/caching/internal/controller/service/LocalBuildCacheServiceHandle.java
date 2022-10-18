package org.gradle.caching.internal.controller.service;

import com.google.common.annotations.VisibleForTesting;
import org.gradle.caching.BuildCacheKey;
import org.gradle.caching.local.internal.LocalBuildCacheService;

import javax.annotation.Nullable;
import java.io.Closeable;
import java.io.File;
import java.util.Optional;
import java.util.function.Function;

public interface LocalBuildCacheServiceHandle extends Closeable {

    @Nullable
    @VisibleForTesting
    LocalBuildCacheService getService();

    // TODO: what if this errors?
    Optional<BuildCacheLoadResult> maybeLoad(BuildCacheKey key, Function<File, BuildCacheLoadResult> unpackFunction);

    boolean canStore();

    /**
     * Stores the file to the local cache.
     *
     * If canStore() returns false, then this method will do nothing and will return false.
     *
     * Returns true if store was completed.
     */
    boolean maybeStore(BuildCacheKey key, File file);

    @Override
    void close();

}