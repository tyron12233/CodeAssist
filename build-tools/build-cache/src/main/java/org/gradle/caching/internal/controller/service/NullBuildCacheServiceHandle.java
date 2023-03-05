package org.gradle.caching.internal.controller.service;

import org.gradle.caching.BuildCacheKey;
import org.gradle.caching.BuildCacheService;

import javax.annotation.Nullable;

public class NullBuildCacheServiceHandle implements BuildCacheServiceHandle {

    public static final BuildCacheServiceHandle INSTANCE = new NullBuildCacheServiceHandle();

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
    public void load(BuildCacheKey key, LoadTarget loadTarget) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean canStore() {
        return false;
    }

    @Override
    public void store(BuildCacheKey key, StoreTarget storeTarget) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void close() {

    }

}
