package com.tyron.builder.cache;

/**
 * Participates in a unit of work that accesses the cache. Implementations do not need to be thread-safe and are accessed by a single thread at a time.
 */
public interface UnitOfWorkParticipant {
    /**
     * Called just after the cache is locked. Called before any work is performed by other threads. This method may access the cache files.
     *
     * @param currentCacheState the current cache state.
     */
    void afterLockAcquire(FileLock.State currentCacheState);

    /**
     * Called when the cache is due to be unlocked. Call after other threads have completed work. This method may access the cache files.
     */
    void finishWork();

    /**
     * Called just before the cache is to be unlocked. Called after all work has been completed, and after {@link #finishWork()} has completed.
     * This method should not modify the cache files.
     */
    void beforeLockRelease(FileLock.State currentCacheState);
}