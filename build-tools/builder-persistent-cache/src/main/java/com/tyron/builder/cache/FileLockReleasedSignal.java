package com.tyron.builder.cache;

/**
 * Signal that a file lock has been released.
 *
 * @see FileLockContentionHandler
 */
public interface FileLockReleasedSignal {

    /**
     * Triggers this signal to notify the lock requesters that the file
     * lock has been released.
     *
     * <p>Returns once the signal has been emitted but not necessarily
     * received by the lock requesters.
     */
    void trigger();

}