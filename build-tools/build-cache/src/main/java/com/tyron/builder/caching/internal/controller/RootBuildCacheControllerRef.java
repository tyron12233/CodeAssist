package com.tyron.builder.caching.internal.controller;

import com.tyron.builder.internal.snapshot.FileSystemSnapshot;
import com.tyron.builder.caching.BuildCacheKey;
import com.tyron.builder.caching.internal.BuildCacheController;
import com.tyron.builder.caching.internal.CacheableEntity;
import com.tyron.builder.caching.internal.controller.service.BuildCacheLoadResult;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

public class RootBuildCacheControllerRef {

    private BuildCacheController buildCacheController;

    public void set(BuildCacheController buildCacheController) {
        // This instance ends up in build/gradle scoped services for nesteds
        // We don't want to invoke close at that time.
        // Instead, close it at the root.
        this.buildCacheController = new CloseShieldBuildCacheController(buildCacheController);
    }

    public BuildCacheController getForNonRootBuild() {
        if (!isSet()) {
            throw new IllegalStateException("Root build cache controller not yet assigned");
        }

        return buildCacheController;
    }

    public boolean isSet() {
        return buildCacheController != null;
    }

    private static class CloseShieldBuildCacheController implements BuildCacheController {
        private final BuildCacheController delegate;

        private CloseShieldBuildCacheController(BuildCacheController delegate) {
            this.delegate = delegate;
        }

        @Override
        public boolean isEnabled() {
            return delegate.isEnabled();
        }

        @Override
        public boolean isEmitDebugLogging() {
            return delegate.isEmitDebugLogging();
        }

        @Override
        public Optional<BuildCacheLoadResult> load(BuildCacheKey cacheKey, CacheableEntity cacheableEntity) {
            return delegate.load(cacheKey, cacheableEntity);
        }

        @Override
        public void store(BuildCacheKey cacheKey, CacheableEntity entity, Map<String, FileSystemSnapshot> snapshots, Duration executionTime) {
            delegate.store(cacheKey, entity, snapshots, executionTime);
        }

        @Override
        public void close() {
        }
    }

}