package com.tyron.psi.completions.lang.java.util.concurrency;

import org.jetbrains.annotations.NotNull;

/**
 * Author: dmitrylomov
 */
public class DefaultResultConsumer<V> implements ResultConsumer<V> {
    private final AsyncFutureResult<? super V> myResult;

    public DefaultResultConsumer(@NotNull AsyncFutureResult<? super V> result) {
        myResult = result;
    }

    @Override
    public void onSuccess(V value) {
        myResult.set(value);
    }

    @Override
    public void onFailure(@NotNull Throwable t) {
        myResult.setException(t);
    }
}