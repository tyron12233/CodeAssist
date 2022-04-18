package com.tyron.builder.cache;

import com.tyron.builder.api.Action;

import org.jetbrains.annotations.Nullable;

import java.io.File;

public interface FileLockManager {
    /**
     * Creates a lock for the given file with the given mode. Acquires a lock with the given mode, which is held until the lock is
     * released by calling {@link FileLock#close()}. This method blocks until the lock can be acquired.
     *
     * @param target The file to be locked.
     * @param options The lock options.
     * @param targetDisplayName A display name for the target file. This is used in log and error messages.
     */
    FileLock lock(File target, LockOptions options, String targetDisplayName) throws LockTimeoutException;

    /**
     * Creates a lock for the given file with the given mode. Acquires a lock with the given mode, which is held until the lock is
     * released by calling {@link FileLock#close()}. This method blocks until the lock can be acquired.
     *
     * @param target The file to be locked.
     * @param options The lock options.
     * @param targetDisplayName A display name for the target file. This is used in log and error messages.
     * @param operationDisplayName A display name for the operation being performed on the target file. This is used in log and error messages.
     */
    FileLock lock(File target, LockOptions options, String targetDisplayName, String operationDisplayName) throws LockTimeoutException;

    /**
     * Creates a lock for the given file with the given mode. Acquires a lock with the given mode, which is held until the lock is
     * released by calling {@link FileLock#close()}. This method blocks until the lock can be acquired.
     * <p>
     * Enable other processes to request access to the provided lock. Provided action runs when the lock access request is received
     * (it means that the lock is contended).
     *
     * @param target The file to be locked.
     * @param options The lock options.
     * @param targetDisplayName A display name for the target file. This is used in log and error messages.
     * @param operationDisplayName A display name for the operation being performed on the target file. This is used in log and error messages.
     * @param whenContended will be called asynchronously by the thread that listens for cache access requests, when such request is received.
     * Note: currently, implementations are permitted to invoke the action <em>after</em> the lock as been closed.
     */
    FileLock lock(File target, LockOptions options, String targetDisplayName, String operationDisplayName, @Nullable Action<FileLockReleasedSignal> whenContended) throws LockTimeoutException;

    enum LockMode {
        /**
         * No synchronisation is done.
         */
        OnDemand,
        /**
         * Multiple readers, no writers.
         */
        Shared,
        /**
         * Single writer, no readers.
         */
        Exclusive,
        /**
         * No locking whatsoever
         */
        None
    }
}