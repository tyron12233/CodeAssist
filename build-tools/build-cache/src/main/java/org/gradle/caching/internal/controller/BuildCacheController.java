package org.gradle.caching.internal.controller;

import org.gradle.caching.BuildCacheKey;
import org.gradle.caching.BuildCacheService;
import org.gradle.caching.internal.CacheableEntity;
import org.gradle.caching.internal.controller.service.BuildCacheLoadResult;
import org.gradle.internal.snapshot.FileSystemSnapshot;

import java.io.Closeable;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/**
 * Internal coordinator of build cache operations.
 *
 * Wraps user {@link BuildCacheService} implementations.
 */
public interface BuildCacheController extends Closeable {

    boolean isEnabled();

    boolean isEmitDebugLogging();

    Optional<BuildCacheLoadResult> load(BuildCacheKey cacheKey, CacheableEntity cacheableEntity);

    void store(BuildCacheKey cacheKey, CacheableEntity entity, Map<String, FileSystemSnapshot> snapshots, Duration executionTime);
}