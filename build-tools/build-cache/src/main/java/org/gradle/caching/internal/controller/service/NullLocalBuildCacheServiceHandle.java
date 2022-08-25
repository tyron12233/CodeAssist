package org.gradle.caching.internal.controller.service;

import org.gradle.caching.BuildCacheKey;
import org.gradle.caching.local.internal.LocalBuildCacheService;

import javax.annotation.Nullable;
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