package com.tyron.builder.internal.work;

import com.tyron.builder.internal.UncheckedException;

import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RunnableFuture;

public abstract class AbstractConditionalExecution<T> implements ConditionalExecution<T> {
    private final CountDownLatch finished = new CountDownLatch(1);
    private final RunnableFuture<T> runnable;

    public AbstractConditionalExecution(final Callable<T> callable) {
        this.runnable = new FutureTask<T>(callable);
    }

    @Override
    public Runnable getExecution() {
        return runnable;
    }

    @Override
    public T await() {
        boolean interrupted = false;
        while (true) {
            try {
                finished.await();
                break;
            } catch (InterruptedException e) {
                cancel();
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }

        try {
            return runnable.get();
        } catch (InterruptedException e) {
            throw UncheckedException.throwAsUncheckedException(e);
        } catch (ExecutionException e) {
            throw UncheckedException.throwAsUncheckedException(e.getCause());
        }
    }

    @Override
    public void complete() {
        finished.countDown();
    }

    @Override
    public boolean isComplete() {
        return finished.getCount() == 0;
    }

    @Override
    public void cancel() {
        runnable.cancel(true);
    }

}
