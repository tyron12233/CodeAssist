package org.gradle.internal.work;

import org.gradle.internal.Factory;

public interface Synchronizer {
    /**
     * Runs the given action while holding the associated resource lock, blocking until the lock can be acquired.
     *
     * Fails if the current thread is already holding the resource lock. May release project locks prior to blocking, as per {@link WorkerLeaseService#blocking(Runnable)}.
     */
    void withLock(Runnable action);

    /**
     * Runs the given action while holding the associated resource lock, blocking until the lock can be acquired.
     *
     * Fails if the current thread is already holding the resource lock. May release project locks prior to blocking, as per {@link WorkerLeaseService#blocking(Runnable)}.
     */
    <T> T withLock(Factory<T> action);
}