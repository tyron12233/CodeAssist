package com.tyron.builder.api.internal.resources;

/**
 * Represents a lock on an abstract resource.  Implementations of this interface should see that methods fail if they are called
 * outside of a {@link ResourceLockCoordinationService#withStateLock(Transformer)} transform.
 */
public interface ResourceLock {
    /**
     * Returns true if this resource is locked by any thread.
     *
     * @return true if any thread holds the lock for this resource
     */
    boolean isLocked();

    /**
     * Returns true if the current thread holds a lock on this resource.  Returns false otherwise.
     *
     * @return true if the task for this operation holds the lock for this resource.
     */
    boolean isLockedByCurrentThread();

    /**
     * Attempt to lock this resource, if not already.  Does not block.
     *
     * @return true if resource is now locked, false otherwise.
     */
    boolean tryLock();

    /**
     * Unlock this resource if it's held by the calling thread.
     */
    void unlock();

    String getDisplayName();
}