package com.tyron.kotlin.completion.core.model;

import com.google.common.util.concurrent.AsyncCallable;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.tyron.completion.progress.ProgressManager;

import org.checkerframework.checker.nullness.qual.Nullable;
import org.jetbrains.concurrency.CancellablePromise;
import org.jetbrains.kotlin.com.intellij.openapi.application.ModalityState;
import org.jetbrains.kotlin.com.intellij.openapi.application.NonBlockingReadAction;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

public class NonBlockingReadActionImpl<T> implements NonBlockingReadAction<T> {

    private final Callable<T> myCallable;

    public NonBlockingReadActionImpl(Callable<T> callable) {
        myCallable = callable;
    }

    @Override
    public NonBlockingReadAction<T> expireWhen(BooleanSupplier booleanSupplier) {
        return new NonBlockingReadActionImpl<>(myCallable);
    }

    @Override
    public NonBlockingReadAction<T> finishOnUiThread(ModalityState modalityState,
                                                     Consumer<? super T> consumer) {
        T call = null;
        try {
            call = myCallable.call();
        } catch (Exception e) {
            e.printStackTrace();
        }
        consumer.accept(call);
        return this;
    }

    @Override
    public NonBlockingReadAction<T> coalesceBy(Object ... objects) {
        return this;
    }

    @Override
    public CancellablePromise<T> submit(Executor executor) {
        Future<T> result = ((ExecutorService) executor).submit(myCallable);
        return new CancellablePromiseWrapper<>(result);
    }
}
