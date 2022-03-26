package com.tyron.builder.api.internal.concurrent;

import java.util.concurrent.Callable;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class ManagedScheduledExecutorImpl extends ManagedExecutorImpl implements ManagedScheduledExecutor {
    private final ScheduledExecutorService executor;

    ManagedScheduledExecutorImpl(ScheduledExecutorService executor, ExecutorPolicy executorPolicy) {
        super(executor, executorPolicy);
        this.executor = executor;
    }

    @Override
    public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
        return executor.schedule(trackedCommand(command), delay, unit);
    }

    @Override
    public <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit) {
        return executor.schedule(trackedCommand(callable), delay, unit);
    }

    @Override
    public ScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initialDelay, long period, TimeUnit unit) {
        return executor.scheduleAtFixedRate(trackedCommand(command), initialDelay, period, unit);
    }

    @Override
    public ScheduledFuture<?> scheduleWithFixedDelay(Runnable command, long initialDelay, long delay, TimeUnit unit) {
        return executor.scheduleWithFixedDelay(trackedCommand(command), initialDelay, delay, unit);
    }
}