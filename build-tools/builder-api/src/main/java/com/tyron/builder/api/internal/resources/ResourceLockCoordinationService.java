package com.tyron.builder.api.internal.resources;

import com.tyron.builder.api.Action;
import com.tyron.builder.api.Transformer;

import java.util.function.Supplier;

public interface ResourceLockCoordinationService {
    /**
     * Gets the current {@link ResourceLockState} active in this thread.  This must be called in the context
     * of a {@link #withStateLock(Transformer)} transform.
     *
     * @return the current {@link ResourceLockState} or null if not in a transform.
     */
    ResourceLockState getCurrent();

    /**
     * Attempts to atomically change the state of resource locks.  Only one thread can alter the resource lock
     * states at one time.  Other threads will block until the resource lock state is free.  The provided
     * {@link Transformer} should return a {@link org.gradle.internal.resources.ResourceLockState.Disposition}
     * that tells the resource coordinator how to proceed:
     *
     * FINISHED - All locks were acquired, release the state lock
     * FAILED - One or more locks were not acquired, roll back any locks that were acquired and release the state lock
     * RETRY - One or more locks were not acquired, roll back any locks that were acquired and block waiting for the
     * state to change, then run the transform again
     *
     * @return true if the lock state changes finished successfully, otherwise false.
     */
    boolean withStateLock(Transformer<ResourceLockState.Disposition, ResourceLockState> stateLockAction);

    /**
     * A convenience for using {@link #withStateLock(Transformer)}.
     */
    void withStateLock(Runnable action);

    /**
     * A convenience for using {@link #withStateLock(Transformer)}.
     */
    <T> T withStateLock(Supplier<T> action);

    /**
     * Notify other threads about changes to resource locks.
     */
    void notifyStateChange();

    void assertHasStateLock();

    /**
     * Adds a listener that is notified when a lock is released. Called while the state lock is held.
     */
    void addLockReleaseListener(Action<ResourceLock> listener);

    void removeLockReleaseListener(Action<ResourceLock> listener);
}
