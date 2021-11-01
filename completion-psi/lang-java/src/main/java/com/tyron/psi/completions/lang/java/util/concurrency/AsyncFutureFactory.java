package com.tyron.psi.completions.lang.java.util.concurrency;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.com.intellij.openapi.application.ApplicationManager;

/**
 * Author: dmitrylomov
 */
public abstract class AsyncFutureFactory {
    public static AsyncFutureFactory getInstance() {
        return ApplicationManager.getApplication().getService(AsyncFutureFactory.class);
    }

    @NotNull
    public static <V> AsyncFuture<V> wrap(V v) {
        final AsyncFutureResult<V> result = getInstance().createAsyncFutureResult();
        result.set(v);
        return result;
    }

    @NotNull
    public static <V> AsyncFuture<V> wrapException(Throwable e) {
        final AsyncFutureResult<V> result = getInstance().createAsyncFutureResult();
        result.setException(e);
        return result;
    }

    @NotNull
    public abstract <V> AsyncFutureResult<V> createAsyncFutureResult();
}
