package org.gradle.caching.internal.controller;

import org.gradle.caching.BuildCacheKey;
import org.gradle.caching.internal.CacheableEntity;
import org.gradle.caching.internal.controller.service.BuildCacheLoadResult;
import org.gradle.internal.snapshot.FileSystemSnapshot;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

public class NoOpBuildCacheController implements BuildCacheController {

    public static final BuildCacheController INSTANCE = new NoOpBuildCacheController();

    private NoOpBuildCacheController() {
    }

    @Override
    public boolean isEnabled() {
        return false;
    }

    @Override
    public boolean isEmitDebugLogging() {
        return false;
    }

    @Override
    public Optional<BuildCacheLoadResult> load(BuildCacheKey cacheKey, CacheableEntity cacheableEntity) {
        return Optional.empty();
    }

    @Override
    public void store(BuildCacheKey cacheKey, CacheableEntity entity, Map<String, FileSystemSnapshot> snapshots, Duration executionTime) {

    }

    @Override
    public void close() {

    }
}