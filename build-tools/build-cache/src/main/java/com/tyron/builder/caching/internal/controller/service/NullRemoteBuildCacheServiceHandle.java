package com.tyron.builder.caching.internal.controller.service;

import com.tyron.builder.caching.BuildCacheKey;
import com.tyron.builder.caching.BuildCacheService;

import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.Optional;
import java.util.function.Function;

public class NullRemoteBuildCacheServiceHandle implements RemoteBuildCacheServiceHandle {

    public static final RemoteBuildCacheServiceHandle INSTANCE = new NullRemoteBuildCacheServiceHandle();

    @Nullable
    @Override
    public BuildCacheService getService() {
        return null;
    }

    @Override
    public boolean canLoad() {
        return false;
    }

    @Override
    public Optional<BuildCacheLoadResult> maybeLoad(BuildCacheKey key, File toFile, Function<File, BuildCacheLoadResult> unpackFunction) {
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
