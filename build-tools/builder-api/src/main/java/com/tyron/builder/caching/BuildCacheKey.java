package com.tyron.builder.caching;

import com.tyron.builder.api.Describable;

/**
 * Cache key identifying an entry in the build cache.
 *
 * @since 3.3
 */
public interface BuildCacheKey extends Describable {
    /**
     * Returns the string representation of the cache key.
     */
    String getHashCode();

    /**
     * Returns the byte array representation of the cache key.
     *
     * @since 5.4
     */
    byte[] toByteArray();
}