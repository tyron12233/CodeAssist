package com.tyron.psi.completions.lang.java.util.concurrency;


import org.jetbrains.annotations.NotNull;

/**
 * Author: dmitrylomov
 */
public interface AsyncFutureResult<V> extends AsyncFuture<V> {
    void set(V value);
    void setException(@NotNull Throwable t);
}

