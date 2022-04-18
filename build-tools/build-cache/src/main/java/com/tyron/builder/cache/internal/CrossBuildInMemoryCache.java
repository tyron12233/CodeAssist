package com.tyron.builder.cache.internal;

import com.tyron.builder.cache.Cache;

import javax.annotation.concurrent.ThreadSafe;
import java.util.function.Function;

/**
 * An in-memory cache of calculated values that are used across builds. The implementation takes care of cleaning up state that is no longer required.
 */
@ThreadSafe
public interface CrossBuildInMemoryCache<K, V> extends Cache<K, V> {
    /**
     * Locates the given entry, using the supplied factory when the entry is not present or has been discarded, to recreate the entry in the cache.
     *
     * <p>Implementations must prevent more than one thread calculating the same key at the same time.
     */
    @Override
    V get(K key, Function<? super K, ? extends V> factory);

    /**
     * Removes all entries from this cache.
     */
    void clear();
}