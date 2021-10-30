package com.tyron.psi.concurrency;

import org.jetbrains.annotations.NotNull;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

public interface Job<T> {
    void cancel();

    boolean isCanceled();

    boolean isDone();

    /**
     * Waits until all work is executed.
     * Note that calling {@link #cancel()} might not lead to this method termination because the job can be in the middle of execution.
     * @throws TimeoutException when timeout expires
     */
    void waitForCompletion(int millis) throws InterruptedException, ExecutionException, TimeoutException;

    @SuppressWarnings("unchecked")
    static <T> Job<T> nullJob() {
        return NULL_JOB;
    }

    @NotNull
    Job NULL_JOB = new Job() {
        @Override
        public boolean isDone() {
            return true;
        }

        @Override
        public void waitForCompletion(int millis) {

        }

        @Override
        public void cancel() {
        }

        @Override
        public boolean isCanceled() {
            return true;
        }
    };
}