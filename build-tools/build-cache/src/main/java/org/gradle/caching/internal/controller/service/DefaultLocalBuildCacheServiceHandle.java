package org.gradle.caching.internal.controller.service;

import org.gradle.api.Action;
import org.gradle.caching.BuildCacheKey;
import org.gradle.caching.local.internal.LocalBuildCacheService;

import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

public class DefaultLocalBuildCacheServiceHandle implements LocalBuildCacheServiceHandle {

    private final LocalBuildCacheService service;
    private final boolean pushEnabled;

    public DefaultLocalBuildCacheServiceHandle(LocalBuildCacheService service, boolean pushEnabled) {
        this.service = service;
        this.pushEnabled = pushEnabled;
    }

    @Nullable
    @Override
    public LocalBuildCacheService getService() {
        return service;
    }

    @Override
    public boolean canLoad() {
        return true;
    }

    @Override
    public void load(BuildCacheKey key, Action<? super File> reader) {
        service.loadLocally(key, reader);
    }

    @Override
    public boolean canStore() {
        return pushEnabled;
    }

    @Override
    public void store(BuildCacheKey key, File file) {
        service.storeLocally(key, file);
    }

    @Override
    public void close() {
        service.close();
    }
}
