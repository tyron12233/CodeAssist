package com.tyron.builder.cache;


import com.tyron.builder.internal.Factory;

public interface CrossProcessCacheAccess {
    /**
     * Runs the given action while this process is holding an exclusive file lock on the cache. Multiple threads may run concurrently.
     */
    <T> T withFileLock(Factory<T> factory);

    /**
     * Acquires an exclusive file lock on the cache. The caller is responsible for running the resulting action to release the lock.
     * The lock may be released by any thread.
     */
    Runnable acquireFileLock();
}