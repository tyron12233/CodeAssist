package com.tyron.builder.cache.internal;

import com.tyron.builder.internal.Factory;
import com.tyron.builder.internal.UncheckedException;
import com.tyron.builder.internal.concurrent.ExecutorPolicy;
import com.tyron.builder.internal.concurrent.Stoppable;
import com.tyron.builder.internal.time.CountdownTimer;
import com.tyron.builder.internal.time.Time;
import com.tyron.builder.cache.AsyncCacheAccess;
import com.tyron.builder.cache.CacheAccess;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

class CacheAccessWorker implements Runnable, Stoppable, AsyncCacheAccess {
    private final BlockingQueue<Runnable> workQueue;
    private final String displayName;
    private final CacheAccess cacheAccess;
    private final long batchWindowMillis;
    private final long maximumLockingTimeMillis;
    private boolean closed;
    private boolean workerCompleted;
    private boolean stopSeen;
    private final CountDownLatch doneSignal = new CountDownLatch(1);
    private final ExecutorPolicy.CatchAndRecordFailures failureHandler = new ExecutorPolicy.CatchAndRecordFailures();

    CacheAccessWorker(String displayName, CacheAccess cacheAccess) {
        this.displayName = displayName;
        this.cacheAccess = cacheAccess;
        this.batchWindowMillis = 200;
        this.maximumLockingTimeMillis = 5000;
        HeapProportionalCacheSizer heapProportionalCacheSizer = new HeapProportionalCacheSizer();
        int queueCapacity = Math.min(4000, heapProportionalCacheSizer.scaleCacheSize(40000));
        workQueue = new ArrayBlockingQueue<Runnable>(queueCapacity, true);
    }

    @Override
    public void enqueue(Runnable task) {
        addToQueue(task);
    }

    private void addToQueue(Runnable task) {
        if (closed) {
            throw new IllegalStateException("The worker has already been closed. Cannot add more work to queue.");
        }
        try {
            workQueue.put(task);
        } catch (InterruptedException e) {
            throw UncheckedException.throwAsUncheckedException(e);
        }
    }

    @Override
    public <T> T read(final Factory<T> task) {
        FutureTask<T> futureTask = new FutureTask<T>(new Callable<T>() {
            @Override
            public T call() throws Exception {
                return task.create();
            }
        });
        addToQueue(futureTask);
        try {
            return futureTask.get();
        } catch (ExecutionException e) {
            throw UncheckedException.throwAsUncheckedException(e.getCause());
        } catch (InterruptedException e) {
            throw UncheckedException.throwAsUncheckedException(e);
        }
    }

    @Override
    public synchronized void flush() {
        if (!workerCompleted && !closed) {
            FlushOperationsCommand flushOperationsCommand = new FlushOperationsCommand();
            addToQueue(flushOperationsCommand);
            flushOperationsCommand.await();
        }
        rethrowFailure();
    }

    private void rethrowFailure() {
        failureHandler.onStop();
    }

    private static class FlushOperationsCommand implements Runnable {
        private CountDownLatch latch = new CountDownLatch(1);

        @Override
        public void run() {
        }

        public void await() {
            try {
                latch.await();
            } catch (InterruptedException e) {
                throw UncheckedException.throwAsUncheckedException(e);
            }
        }

        public void completed() {
            latch.countDown();
        }
    }

    @Override
    public void run() {
        try {
            while (!Thread.currentThread().isInterrupted() && !stopSeen) {
                try {
                    Runnable runnable = takeFromQueue();
                    Class<? extends Runnable> runnableClass = runnable.getClass();
                    if (runnableClass == ShutdownOperationsCommand.class) {
                        // not holding the cache lock, can stop now
                        stopSeen = true;
                        break;
                    } else if (runnableClass == FlushOperationsCommand.class) {
                        // not holding the cache lock, flush is done so notify flush thread and continue
                        FlushOperationsCommand flushOperationsCommand = (FlushOperationsCommand) runnable;
                        flushOperationsCommand.completed();
                    } else {
                        // need to run operation under cache lock
                        flushOperations(runnable);
                    }
                } catch (InterruptedException e) {
                    throw UncheckedException.throwAsUncheckedException(e);
                }
            }
        } catch (Throwable t) {
            failureHandler.onFailure("Failed to execute cache operations on " + displayName, t);
        } finally {
            // Notify any waiting flush threads that the worker is done, possibly with a failure
            List<Runnable> runnables = new ArrayList<Runnable>();
            workQueue.drainTo(runnables);
            for (Runnable runnable : runnables) {
                if (runnable instanceof FlushOperationsCommand) {
                    FlushOperationsCommand flushOperationsCommand = (FlushOperationsCommand) runnable;
                    flushOperationsCommand.completed();
                }
            }
            workerCompleted = true;
            doneSignal.countDown();
        }
    }

    private Runnable takeFromQueue() throws InterruptedException {
        return workQueue.take();
    }

    private void flushOperations(final Runnable updateOperation) {
        final List<FlushOperationsCommand> flushOperations = new ArrayList<FlushOperationsCommand>();
        try {
            cacheAccess.useCache(new Runnable() {
                @Override
                public void run() {
                    CountdownTimer timer = Time.startCountdownTimer(maximumLockingTimeMillis, TimeUnit.MILLISECONDS);
                    if (updateOperation != null) {
                        failureHandler.onExecute(updateOperation);
                    }
                    Runnable otherOperation;
                    try {
                        while ((otherOperation = workQueue.poll(batchWindowMillis, TimeUnit.MILLISECONDS)) != null) {
                            failureHandler.onExecute(otherOperation);
                            final Class<? extends Runnable> runnableClass = otherOperation.getClass();
                            if (runnableClass == FlushOperationsCommand.class) {
                                flushOperations.add((FlushOperationsCommand) otherOperation);
                            }
                            if (runnableClass == ShutdownOperationsCommand.class) {
                                stopSeen = true;
                            }
                            if (runnableClass == ShutdownOperationsCommand.class
                                || runnableClass == FlushOperationsCommand.class
                                || timer.hasExpired()) {
                                break;
                            }
                        }
                    } catch (InterruptedException e) {
                        throw UncheckedException.throwAsUncheckedException(e);
                    }
                }
            });
        } finally {
            for (FlushOperationsCommand flushOperation : flushOperations) {
                flushOperation.completed();
            }
        }
    }

    @Override
    public synchronized void stop() {
        if (!closed && !workerCompleted) {
            closed = true;
            try {
                workQueue.put(new ShutdownOperationsCommand());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            try {
                doneSignal.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        rethrowFailure();
    }

    private static class ShutdownOperationsCommand implements Runnable {
        @Override
        public void run() {
            // do nothing
        }
    }
}