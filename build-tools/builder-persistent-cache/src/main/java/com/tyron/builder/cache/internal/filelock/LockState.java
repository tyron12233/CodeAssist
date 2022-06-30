package com.tyron.builder.cache.internal.filelock;

import com.tyron.builder.cache.FileLock;

/**
 * An immutable snapshot of the state of a lock.
 */
public interface LockState extends FileLock.State {
    boolean isDirty();

    @Override
    boolean isInInitialState();

    /**
     * Called after an update is complete, returns a new clean state based on this state.
     */
    LockState completeUpdate();

    /**
     * Called before an update is complete, returns a new dirty state based on this state.
     */
    LockState beforeUpdate();
}