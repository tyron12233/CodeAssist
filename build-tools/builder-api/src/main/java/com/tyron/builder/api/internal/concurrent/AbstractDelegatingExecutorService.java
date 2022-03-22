package com.tyron.builder.api.internal.concurrent;

import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

public class AbstractDelegatingExecutorService extends AbstractExecutorService {
    private final ExecutorService delegate;

    public AbstractDelegatingExecutorService(ExecutorService delegate) {
        this.delegate = delegate;
    }

    @Override
    public void shutdown() {
        delegate.shutdown();
    }

    @Override
    public List<Runnable> shutdownNow() {
        return delegate.shutdownNow();
    }

    @Override
    public boolean isShutdown() {
        return delegate.isShutdown();
    }

    @Override
    public boolean isTerminated() {
        return delegate.isTerminated();
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        return delegate.awaitTermination(timeout, unit);
    }

    @Override
    public void execute(Runnable command) {
        delegate.execute(command);
    }
}
