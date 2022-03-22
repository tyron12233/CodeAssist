package com.tyron.builder.api.internal.work;

import com.tyron.builder.api.internal.Factory;
import com.tyron.builder.api.internal.resources.ProjectLeaseRegistry;
import com.tyron.builder.api.internal.resources.ResourceLock;

public interface WorkerLeaseService extends WorkerLeaseRegistry, ProjectLeaseRegistry, WorkerThreadRegistry {
    /**
     * Returns the maximum number of worker leases that this service will grant at any given time. Note that the actual limit may vary over time but will never _exceed_ the value returned by this method.
     */
    int getMaxWorkerCount();

    /**
     * Runs a given {@link Factory} while the specified locks are being held, releasing
     * the locks upon completion.  Blocks until the specified locks can be obtained.
     */
    <T> T withLocks(Iterable<? extends ResourceLock> locks, Factory<T> factory);

    /**
     * Runs a given {@link Runnable} while the specified locks are being held, releasing
     * the locks upon completion.  Blocks until the specified locks can be obtained.
     */
    void withLocks(Iterable<? extends ResourceLock> locks, Runnable runnable);

    /**
     * Runs a given {@link Factory} while the specified locks are released and then reacquire the locks
     * upon completion.  If the locks cannot be immediately reacquired, the current worker lease will be released
     * and the method will block until the locks are reacquired.
     */
    <T> T withoutLocks(Iterable<? extends ResourceLock> locks, Factory<T> factory);

    /**
     * Runs a given {@link Runnable} while the specified locks are released and then reacquire the locks
     * upon completion.  If the locks cannot be immediately reacquired, the current worker lease will be released
     * and the method will block until the locks are reacquired.
     */
    void withoutLocks(Iterable<? extends ResourceLock> locks, Runnable runnable);

    Synchronizer newResource();
}