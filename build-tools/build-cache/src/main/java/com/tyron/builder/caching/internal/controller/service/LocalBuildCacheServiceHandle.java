package com.tyron.builder.caching.internal.controller.service;

import com.google.common.annotations.VisibleForTesting;
import com.tyron.builder.api.Action;
import com.tyron.builder.caching.BuildCacheKey;
import com.tyron.builder.caching.local.internal.LocalBuildCacheService;

import org.jetbrains.annotations.Nullable;

import java.io.Closeable;
import java.io.File;

public interface LocalBuildCacheServiceHandle extends Closeable {

    @Nullable
    @VisibleForTesting
    LocalBuildCacheService getService();

    boolean canLoad();

    // TODO: what if this errors?
    void load(BuildCacheKey key, Action<? super File> reader);

    boolean canStore();

    // TODO: what if this errors?
    void store(BuildCacheKey key, File file);

    @Override
    void close();

}
