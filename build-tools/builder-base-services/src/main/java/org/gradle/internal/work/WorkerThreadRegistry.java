package org.gradle.internal.work;

import org.gradle.internal.Factory;
import org.gradle.internal.service.scopes.Scopes;
import org.gradle.internal.service.scopes.ServiceScope;

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
     *
     * This method blocks until a worker lease is available.
     */
    <T> T runAsWorkerThread(Factory<T> action);

    /**
     * Runs the given action as a worker. While the action is running, the thread can acquire resource locks.
     * A worker lease is also granted to the thread. The thread can release this if it needs to, but should reacquire
     * the lease prior to doing any meaningful work.
     *
     * This method is reentrant so that a thread can call this method from the given action.
     *
     * This method blocks until a worker lease is available.
     */
    void runAsWorkerThread(Runnable action);

    /**
     * Runs the given action as an unmanaged worker, if not already a worker. This is basically the same as {@link #runAsWorkerThread(Runnable)} but does not block waiting for a lease.
     * Instead, a temporary lease is granted to the current thread.
     *
     * You should avoid using this method and prefer {@link #runAsWorkerThread(Runnable)} instead. This method is here to allow some backwards compatibility constraints to be honored.
     */
    void runAsUnmanagedWorkerThread(Runnable action);

    /**
     * Starts a new lease for the current thread. Marks the reservation of a lease. Blocks until a lease is available.
     *
     * <p>Note that the caller must call {@link WorkerLeaseRegistry.WorkerLeaseCompletion#leaseFinish()} to mark the completion of the lease and to release the lease for other threads to use.
     *
     * <p>It is generally better to use {@link WorkerThreadRegistry#runAsWorkerThread(Runnable)} instead of this method.</p>
     */
    WorkerLeaseRegistry.WorkerLeaseCompletion startWorker();

    /**
     * Returns the current worker lease for the current thread or starts a new one if the current thread is not a worker.
     *
     * <p>Note that the caller must call {@link WorkerLeaseRegistry.WorkerLeaseCompletion#leaseFinish()} to mark the completion of the lease and to release the lease for other threads to use.
     *
     * <p>It is generally better to use {@link WorkerThreadRegistry#runAsWorkerThread(Runnable)} instead of this method.</p>
     */
    WorkerLeaseRegistry.WorkerLeaseCompletion maybeStartWorker();

    /**
     * Returns {@code true} when this thread is enlisted in resource locking.
     */
    boolean isWorkerThread();
}