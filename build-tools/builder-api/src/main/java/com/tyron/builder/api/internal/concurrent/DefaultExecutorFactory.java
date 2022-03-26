package com.tyron.builder.api.internal.concurrent;

import org.jetbrains.annotations.Nullable;

import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

public class DefaultExecutorFactory implements ExecutorFactory, Stoppable {
    private final Set<ManagedExecutor> executors = new CopyOnWriteArraySet<ManagedExecutor>();
    @Nullable
    private final ClassLoader threadFactoryContextClassloader;

    public DefaultExecutorFactory() {
        this(Thread.currentThread().getContextClassLoader());
    }

    public DefaultExecutorFactory(@Nullable ClassLoader threadFactoryContextClassloader) {
        this.threadFactoryContextClassloader = threadFactoryContextClassloader;
    }

    @Override
    public void stop() {
        try {
            CompositeStoppable.stoppable(executors).stop();
        } finally {
            executors.clear();
        }
    }

    @Override
    public ManagedExecutor create(String displayName) {
        ManagedExecutor executor = new TrackedManagedExecutor(createExecutor(displayName), new ExecutorPolicy.CatchAndRecordFailures());
        executors.add(executor);
        return executor;
    }

    protected ExecutorService createExecutor(String displayName) {
        return Executors.newCachedThreadPool(newThreadFactory(displayName));
    }

    @Override
    public ManagedExecutor create(String displayName, int fixedSize) {
        TrackedManagedExecutor executor = new TrackedManagedExecutor(createExecutor(displayName, fixedSize), new ExecutorPolicy.CatchAndRecordFailures());
        executors.add(executor);
        return executor;
    }

    protected ExecutorService createExecutor(String displayName, int fixedSize) {
        return Executors.newFixedThreadPool(fixedSize, newThreadFactory(displayName));
    }

    @Override
    public ManagedScheduledExecutor createScheduled(String displayName, int fixedSize) {
        ManagedScheduledExecutor executor = new TrackedScheduledManagedExecutor(createScheduledExecutor(displayName, fixedSize), new ExecutorPolicy.CatchAndRecordFailures());
        executors.add(executor);
        return executor;
    }

    private ScheduledExecutorService createScheduledExecutor(String displayName, int fixedSize) {
        return new ScheduledThreadPoolExecutor(fixedSize, newThreadFactory(displayName));
    }

    private ThreadFactory newThreadFactory(String displayName) {
        return new ThreadFactoryImpl(displayName, threadFactoryContextClassloader);
    }

    private class TrackedManagedExecutor extends ManagedExecutorImpl {
        TrackedManagedExecutor(ExecutorService executor, ExecutorPolicy executorPolicy) {
            super(executor, executorPolicy);
        }

        @Override
        public void stop(int timeoutValue, TimeUnit timeoutUnits) throws IllegalStateException {
            try {
                super.stop(timeoutValue, timeoutUnits);
            } finally {
                executors.remove(this);
            }
        }
    }

    private class TrackedScheduledManagedExecutor extends ManagedScheduledExecutorImpl {
        TrackedScheduledManagedExecutor(ScheduledExecutorService executor, ExecutorPolicy executorPolicy) {
            super(executor, executorPolicy);
        }

        @Override
        public void stop(int timeoutValue, TimeUnit timeoutUnits) throws IllegalStateException {
            try {
                super.stop(timeoutValue, timeoutUnits);
            } finally {
                executors.remove(this);
            }
        }
    }
}