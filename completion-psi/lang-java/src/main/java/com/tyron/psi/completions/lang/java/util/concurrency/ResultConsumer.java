package com.tyron.psi.completions.lang.java.util.concurrency;

import org.jetbrains.annotations.NotNull;

/**
 * Author: dmitrylomov
 */
public interface ResultConsumer<V> {
    void onSuccess(V value);
    void onFailure(@NotNull Throwable t);
}

