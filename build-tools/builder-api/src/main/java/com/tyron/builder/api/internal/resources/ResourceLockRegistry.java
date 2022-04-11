package com.tyron.builder.api.internal.resources;

import java.util.Collection;

public interface ResourceLockRegistry {
    /**
     * Get all of the resource locks held by the current thread.
     */
    Collection<? extends ResourceLock> getResourceLocksByCurrentThread();

    /**
     * Returns true if the registry has any locks that are being held by a thread.
     *
     * @return true if any locks in the registry are currently held.
     */
    boolean hasOpenLocks();
}