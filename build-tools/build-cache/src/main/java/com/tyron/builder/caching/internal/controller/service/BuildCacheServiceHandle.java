package com.tyron.builder.caching.internal.controller.service;

import com.google.common.annotations.VisibleForTesting;
import com.tyron.builder.caching.BuildCacheKey;
import com.tyron.builder.caching.BuildCacheService;

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