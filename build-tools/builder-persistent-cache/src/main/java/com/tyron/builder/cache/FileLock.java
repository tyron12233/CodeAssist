package com.tyron.builder.cache;

import java.io.Closeable;
import java.io.File;

public interface FileLock extends Closeable, FileAccess {
    /**
     * Returns true if the most recent mutation method ({@link #updateFile(Runnable)} or {@link #writeFile(Runnable)} attempted by any process succeeded
     * (ie a process did not crash while updating the target file).
     *
     * Returns false if no mutation method has ever been called for the target file.
     */
    boolean getUnlockedCleanly();

    /**
     * Returns true if the given file is used by this lock.
     */
    boolean isLockFile(File file);

    /**
     * Closes this lock, releasing the lock and any resources associated with it.
     */
    @Override
    void close();

    /**
     * Returns some memento of the current state of this target file.
     */
    State getState();

    /**
     * The actual mode of the lock. May be different to what was requested.
     */
    FileLockManager.LockMode getMode();

    /**
     * An immutable snapshot of the state of a lock.
     */
    interface State {
        boolean canDetectChanges();
        boolean isInInitialState();
        boolean hasBeenUpdatedSince(State state);
    }
}