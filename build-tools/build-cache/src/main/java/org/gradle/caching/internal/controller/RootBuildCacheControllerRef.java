package org.gradle.caching.internal.controller;

import org.gradle.internal.snapshot.FileSystemSnapshot;
import org.gradle.caching.BuildCacheKey;
import org.gradle.caching.internal.CacheableEntity;
import org.gradle.caching.internal.controller.service.BuildCacheLoadResult;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

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
        public <T> Optional<T> load(BuildCacheLoadCommand<T> command) {
            return delegate.load(command);
        }

        @Override
        public void store(BuildCacheStoreCommand command) {
            delegate.store(command);
        }

        @Override
        public void close() {
        }
    }

}
