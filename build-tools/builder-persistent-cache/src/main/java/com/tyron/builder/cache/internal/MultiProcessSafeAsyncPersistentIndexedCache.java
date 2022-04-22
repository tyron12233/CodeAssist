package com.tyron.builder.cache.internal;

import com.tyron.builder.cache.UnitOfWorkParticipant;

import org.jetbrains.annotations.Nullable;

import java.util.function.Function;

/**
 * An indexed cache that may perform updates asynchronously.
 */
public interface MultiProcessSafeAsyncPersistentIndexedCache<K, V> extends UnitOfWorkParticipant {
    /**
     * Fetches the given entry, blocking until the result is available.
     */
    @Nullable
    V get(K key);

    /**
     * Fetches the given entry, producing if necessary, blocking until the result is available. This method may or may not block until any updates have completed and will invoke the given completion action when the operation is complete.
     */
    V get(K key, Function<? super K, ? extends V> producer, Runnable completion);

    /**
     * Submits an update to be applied later. This method may or may not block, and will invoke the given completion action when the operation is complete.
     */
    void putLater(K key, V value, Runnable completion);

    /**
     * Submits a removal to be applied later. This method may or may not block, and will invoke the given completion action when the operation is complete.
     */
    void removeLater(K key, Runnable completion);
}