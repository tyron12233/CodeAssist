package org.gradle.internal.execution.history;

import org.gradle.cache.PersistentCache;

import java.util.function.Supplier;

/**
 * Provides access to the persistent execution history store.
 */
public interface ExecutionHistoryCacheAccess extends Supplier<PersistentCache> {
}