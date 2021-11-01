package com.tyron.psi.completions.lang.java.util.concurrency;

import org.jetbrains.annotations.NotNull;

import java.util.concurrent.Executor;
import java.util.concurrent.Future;

/**
 * Author: dmitrylomov
 */
public interface AsyncFuture<V> extends Future<V> {
    void addConsumer(@NotNull Executor executor, @NotNull ResultConsumer<? super V> consumer);
}
