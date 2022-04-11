package com.tyron.builder.api.internal.work;

import com.tyron.builder.api.internal.Factory;

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