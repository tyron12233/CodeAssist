package com.tyron.builder.api.internal.resources;

public interface ResourceLockState {
    /**
     * Possible results from a resource lock state transform.
     */
    enum Disposition { FAILED, FINISHED, RETRY }

    /**
     * Registers a resource lock to be rolled back if the transform associated with this resource lock state
     * fails.
     *
     * @param resourceLock
     */
    void registerLocked(ResourceLock resourceLock);

    /**
     * Registers a resource lock that has been unlocked during the transform so that the coordination service can
     * notify threads waiting on a lock.
     *
     * @param resourceLock
     */
    void registerUnlocked(ResourceLock resourceLock);

    /**
     * Release any locks that have been acquired during the transform.
     */
    void releaseLocks();
}