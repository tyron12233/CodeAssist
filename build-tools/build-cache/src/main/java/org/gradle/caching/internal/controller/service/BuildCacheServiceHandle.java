package org.gradle.caching.internal.controller.service;

import com.google.common.annotations.VisibleForTesting;
import org.gradle.caching.BuildCacheKey;
import org.gradle.caching.BuildCacheService;

import javax.annotation.Nullable;
import java.io.Closeable;

public interface BuildCacheServiceHandle extends Closeable {

    @Nullable
    @VisibleForTesting
    BuildCacheService getService();

    boolean canLoad();

    void load(BuildCacheKey key, LoadTarget loadTarget);

    boolean canStore();

    void store(BuildCacheKey key, StoreTarget storeTarget);

    @Override
    void close();
}