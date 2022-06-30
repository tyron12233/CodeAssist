package com.tyron.builder.cache.internal;

public interface CacheCleanupAction {
    /**
     * Determines if this action should run. Called when the cache is closed, holding an exclusive lock.
     */
    boolean requiresCleanup();

    /**
     * Executes the action to cleanup the cache. Called only if {@link #requiresCleanup()} returns true, holding an exclusive lock.
     * The lock is not released between calling {@link #requiresCleanup()} and this method.
     */
    void cleanup();
}