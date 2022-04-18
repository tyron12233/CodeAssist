package com.tyron.builder.caching.internal.controller.service;

import com.tyron.builder.caching.BuildCacheKey;
import com.tyron.builder.caching.local.internal.LocalBuildCacheService;

import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.Optional;
import java.util.function.Function;

public class NullLocalBuildCacheServiceHandle implements LocalBuildCacheServiceHandle {

    public static final LocalBuildCacheServiceHandle INSTANCE = new NullLocalBuildCacheServiceHandle();

    private NullLocalBuildCacheServiceHandle() {
    }

    @Nullable
    @Override
    public LocalBuildCacheService getService() {
        return null;
    }

    @Override
    public Optional<BuildCacheLoadResult> maybeLoad(BuildCacheKey key, Function<File, BuildCacheLoadResult> unpackFunction) {
        return Optional.empty();
    }

    @Override
    public boolean canStore() {
        return false;
    }

    @Override
    public boolean maybeStore(BuildCacheKey key, File file) {
        return false;
    }

    @Override
    public void close() {

    }
}