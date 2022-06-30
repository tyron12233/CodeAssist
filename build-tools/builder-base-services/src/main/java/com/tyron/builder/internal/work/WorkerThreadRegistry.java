package com.tyron.builder.internal.work;

import com.tyron.builder.internal.Factory;
import com.tyron.builder.internal.service.scopes.Scopes;
import com.tyron.builder.internal.service.scopes.ServiceScope;

/**
 * Allows a thread to enlist in resource locking, for example to lock the mutable state of a project.
 */
@ServiceScope(Scopes.BuildSession.class)
public interface WorkerThreadRegistry {
    /**
     * Runs the given action as a worker. While the action is running, the thread can acquire resource locks.
     * A worker lease is also granted to the thread. The thread can release this if it needs to, but should reacquire
     * the lease prior to doing any meaningful work.
     *
     * This method is reentrant so that a thread can call this method from the given action.
     */
    <T> T runAsWorkerThread(Factory<T> action);

    /**
     * Runs the given action as a worker. While the action is running, the thread can acquire resource locks.
     * A worker lease is also granted to the thread. The thread can release this if it needs to, but should reacquire
     * the lease prior to doing any meaningful work.
     *
     * This method is reentrant so that a thread can call this method from the given action.
     */
    void runAsWorkerThread(Runnable action);

    /**
     * Returns {@code true} when this thread is enlisted in resource locking.
     */
    boolean isWorkerThread();
}
