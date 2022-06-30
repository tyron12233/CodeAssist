package com.tyron.builder.caching.internal.controller.service;

import com.tyron.builder.api.Action;
import com.tyron.builder.caching.BuildCacheKey;
import com.tyron.builder.caching.local.internal.LocalBuildCacheService;

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
