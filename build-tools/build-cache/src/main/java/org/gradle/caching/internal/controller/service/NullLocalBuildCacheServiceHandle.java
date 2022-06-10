package org.gradle.caching.internal.controller.service;

import org.gradle.api.Action;
import org.gradle.caching.BuildCacheKey;
import org.gradle.caching.local.internal.LocalBuildCacheService;

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
    public boolean canLoad() {
        return false;
    }

    @Override
    public void load(BuildCacheKey key, Action<? super File> reader) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean canStore() {
        return false;
    }

    @Override
    public void store(BuildCacheKey key, File file) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void close() {

    }
}
