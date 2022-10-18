package com.tyron.builder.compiler.manifest;

import org.jetbrains.annotations.Nullable;

/**
 * Facility to identify an element from its key.
 */
public interface KeyResolver<T> {

    /**
     * Returns an element identified with the passed key.
     * @param key key to resolve.
     * @return the element identified by the passed key or null if there is no key of that name.
     */
    @Nullable
    T resolve(String key);

    Iterable<String> getKeys();
}

