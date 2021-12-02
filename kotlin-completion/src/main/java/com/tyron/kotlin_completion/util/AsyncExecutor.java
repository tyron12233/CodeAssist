package com.tyron.kotlin_completion.util;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import kotlin.jvm.functions.Function0;

public class AsyncExecutor {

    private int threadCount = 0;

    private final ExecutorService workerThread = Executors.newSingleThreadExecutor(runnable ->
            new Thread(runnable, "async" + threadCount++));

    public void execute(Runnable task) {
        CompletableFuture.runAsync(task, workerThread);
    }

    public <R> CompletableFuture<R> compute(Function0<R> task) {
        return CompletableFuture.supplyAsync(task::invoke, workerThread);
    }

    public void shutdown(boolean await) {
        workerThread.shutdown();;
        if (await) {
            try {
                workerThread.awaitTermination(Long.MAX_VALUE, TimeUnit.DAYS);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }
}
