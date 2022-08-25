package org.gradle.caching.internal.controller.service;

import org.gradle.caching.BuildCacheKey;
import org.gradle.caching.local.internal.LocalBuildCacheService;

import javax.annotation.Nullable;
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
    public Optional<BuildCacheLoadResult> maybeLoad(BuildCacheKey key, Function<File, BuildCacheLoadResult> unpackFunction) {
        AtomicReference<Optional<BuildCacheLoadResult>> result = new AtomicReference<>(Optional.empty());
        service.loadLocally(key, file -> result.set(Optional.ofNullable(unpackFunction.apply(file))));
        return result.get();
    }

    @Override
    public boolean canStore() {
        return pushEnabled;
    }

    @Override
    public boolean maybeStore(BuildCacheKey key, File file) {
        if (canStore()) {
            service.storeLocally(key, file);
            return true;
        }
        return false;
    }

    @Override
    public void close() {
        service.close();
    }
}