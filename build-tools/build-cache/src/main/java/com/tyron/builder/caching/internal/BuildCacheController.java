package com.tyron.builder.caching.internal;

import com.tyron.builder.internal.snapshot.FileSystemSnapshot;
import com.tyron.builder.caching.BuildCacheKey;
import com.tyron.builder.caching.BuildCacheService;
import com.tyron.builder.caching.internal.controller.service.BuildCacheLoadResult;

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