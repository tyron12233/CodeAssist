package com.tyron.builder.caching.internal.controller;

import com.tyron.builder.caching.BuildCacheService;

import java.io.Closeable;
import java.util.Optional;

/**
 * Internal coordinator of build cache operations.
 *
 * Wraps user {@link BuildCacheService} implementations.
 */
public interface BuildCacheController extends Closeable {

    boolean isEnabled();

    boolean isEmitDebugLogging();

    <T> Optional<T> load(BuildCacheLoadCommand<T> command);

    void store(BuildCacheStoreCommand command);

}
