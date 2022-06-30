package com.tyron.builder.cache;

import com.tyron.builder.internal.Factory;

import java.util.concurrent.Callable;

/**
 * Provides synchronization with other processes for a particular file.
 */
public interface FileAccess {
    /**
     * Runs the given action under a shared or exclusive lock on the target file.
     *
     * @throws LockTimeoutException On timeout acquiring lock, if required.
     * @throws IllegalStateException When this lock has been closed.
     * @throws FileIntegrityViolationException If the integrity of the file cannot be guaranteed (i.e. {@link #writeFile(Runnable)} has never been called)
     * @throws InsufficientLockModeException If the held lock is not at least a shared lock (e.g. LockMode.NONE)
     */
    <T> T readFile(Callable<? extends T> action) throws LockTimeoutException, FileIntegrityViolationException, InsufficientLockModeException;

    /**
     * Runs the given action under a shared or exclusive lock on the target file.
     *
     * @throws LockTimeoutException On timeout acquiring lock, if required.
     * @throws IllegalStateException When this lock has been closed.
     * @throws FileIntegrityViolationException If the integrity of the file cannot be guaranteed (i.e. {@link #writeFile(Runnable)} has never been called)
     * @throws InsufficientLockModeException If the held lock is not at least a shared lock (e.g. LockMode.NONE)
     */
    <T> T readFile(Factory<? extends T> action) throws LockTimeoutException, FileIntegrityViolationException, InsufficientLockModeException;

    /**
     * Runs the given action under an exclusive lock on the target file. If the given action fails, the lock is marked as uncleanly unlocked.
     *
     * @throws LockTimeoutException On timeout acquiring lock, if required.
     * @throws IllegalStateException When this lock has been closed.
     * @throws FileIntegrityViolationException If the integrity of the file cannot be guaranteed (i.e. {@link #writeFile(Runnable)} has never been called)
     * @throws InsufficientLockModeException If the held lock is not an exclusive lock.
     */
    void updateFile(Runnable action) throws LockTimeoutException, FileIntegrityViolationException, InsufficientLockModeException;

    /**
     * Runs the given action under an exclusive lock on the target file, without checking its integrity. If the given action fails, the lock is marked as uncleanly unlocked.
     *
     * <p>This method should be used when it is of no consequence if the target was not previously unlocked, e.g. the content is being replaced.
     *
     * <p>Besides not performing integrity checking, this method shares the locking semantics of {@link #updateFile(Runnable)}
     *
     * @throws LockTimeoutException On timeout acquiring lock, if required.
     * @throws IllegalStateException When this lock has been closed.
     * @throws InsufficientLockModeException If the held lock is not an exclusive lock.
     */
    void writeFile(Runnable action) throws LockTimeoutException, InsufficientLockModeException;

}