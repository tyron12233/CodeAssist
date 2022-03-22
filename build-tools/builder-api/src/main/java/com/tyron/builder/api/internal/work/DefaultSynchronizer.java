package com.tyron.builder.api.internal.work;

import com.tyron.builder.api.internal.Factory;

class DefaultSynchronizer implements Synchronizer {
    private final WorkerLeaseService workerLeaseService;
    private Thread owner;

    public DefaultSynchronizer(WorkerLeaseService workerLeaseService) {
        this.workerLeaseService = workerLeaseService;
    }

    @Override
    public void withLock(Runnable action) {
        Thread previous = takeOwnership();
        try {
            action.run();
        } finally {
            releaseOwnership(previous);
        }
    }

    @Override
    public <T> T withLock(Factory<T> action) {
        Thread previous = takeOwnership();
        try {
            return action.create();
        } finally {
            releaseOwnership(previous);
        }
    }

    private Thread takeOwnership() {
        final Thread currentThread = Thread.currentThread();
        if (!workerLeaseService.isWorkerThread()) {
            throw new IllegalStateException(
                    "The current thread is not registered as a worker thread.");
        }
        synchronized (this) {
            if (owner == null) {
                owner = currentThread;
                return null;
            } else if (owner == currentThread) {
                return currentThread;
            }
        }
        workerLeaseService.blocking(new Runnable() {
            @Override
            public void run() {
                synchronized (DefaultSynchronizer.this) {
                    while (owner != null) {
                        try {
                            DefaultSynchronizer.this.wait();
                        } catch (InterruptedException e) {
                            throw new RuntimeException(e);
                        }
                    }
                    owner = currentThread;
                }
            }
        });
        return null;
    }

    private void releaseOwnership(Thread previousOwner) {
        synchronized (this) {
            owner = previousOwner;
            this.notifyAll();
        }
    }
}