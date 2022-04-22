package com.tyron.builder.internal.execution.history;

import com.tyron.builder.cache.PersistentCache;

import java.util.function.Supplier;

/**
 * Provides access to the persistent execution history store.
 */
public interface ExecutionHistoryCacheAccess extends Supplier<PersistentCache> {
}