package com.tyron.builder.cache.internal.locklistener;

import com.tyron.builder.api.Action;
import com.tyron.builder.cache.FileLockReleasedSignal;

import org.jetbrains.annotations.Nullable;

public interface FileLockContentionHandler {
    void start(long lockId, Action<FileLockReleasedSignal> whenContended);

    void stop(long lockId);

    int reservePort();

    /**
     * Pings the lock owner with the give port to start the lock releasing
     * process in the owner. May not ping the owner if:
     * - The owner was already pinged about the given lock before and the lock release is in progress
     * - The ping through the underlying socket failed
     *
     * @return true if the owner was pinged in this call
     */
    boolean maybePingOwner(int port, long lockId, String displayName, long timeElapsed, @Nullable FileLockReleasedSignal signal);
}