package com.tyron.builder.cache.internal;

import com.tyron.builder.cache.FileLock;

public interface CacheInitializationAction {
    /**
     * Determines if this action should run. Called when the cache is opened, holding either a shared or exclusive lock. May be called multiple times.
     */
    boolean requiresInitialization(FileLock fileLock);

    /**
     * Executes the action to initialize the cache. Called only if {@link #requiresInitialization(FileLock)} returns true, holding an exclusive lock.
     * The lock is not released between calling {@link #requiresInitialization(FileLock)} and this method.
     */
    void initialize(FileLock fileLock);
}
