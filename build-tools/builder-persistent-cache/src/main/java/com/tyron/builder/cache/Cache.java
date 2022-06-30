package com.tyron.builder.cache;

import org.jetbrains.annotations.Nullable;

import java.util.function.Function;
import java.util.function.Supplier;

public interface Cache<K, V>  {
    /**
     * Locates the given entry, using the supplied factory when the entry is not present or has been discarded, to recreate the entry in the cache.
     *
     * <p>Implementations may prevent more than one thread calculating the same key at the same time or not.
     */
    V get(K key, Function<? super K, ? extends V> factory);

    default V get(K key, Supplier<? extends V> supplier) {
        return get(key, __ -> supplier.get());
    }

    /**
     * Locates the given entry, if present. Returns {@code null} when missing.
     */
    @Nullable
    V getIfPresent(K key);

    /**
     * Adds the given value to the cache, replacing any existing value.
     */
    void put(K key, V value);
}