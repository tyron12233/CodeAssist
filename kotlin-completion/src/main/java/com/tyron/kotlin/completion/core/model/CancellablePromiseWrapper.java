package com.tyron.kotlin.completion.core.model;

import org.jetbrains.concurrency.CancellablePromise;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class CancellablePromiseWrapper<T>  implements CancellablePromise<T> {

    private final Future<T> result;

    public CancellablePromiseWrapper(Future<T> result) {
        this.result = result;
    }

    @Override
    public boolean cancel(boolean mayInterruptIfRunning) {
        return result.cancel(mayInterruptIfRunning);
    }

    @Override
    public boolean isCancelled() {
        return result.isCancelled();
    }

    @Override
    public boolean isDone() {
        return result.isDone();
    }

    @Override
    public T get() throws ExecutionException, InterruptedException {
        return result.get();
    }

    @Override
    public T get(long timeout,
                 TimeUnit unit) throws ExecutionException, InterruptedException, TimeoutException {
        return result.get(timeout, unit);
    }
}
