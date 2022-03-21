package com.tyron.builder.api.internal;

import org.jetbrains.annotations.Nullable;

/**
 * A generic factory which creates instances of type T.
 *
 * @param <T> The type of object created.
 */
public interface Factory<T> {
    /**
     * Creates a new instance of type T.
     * @return The instance.
     */
    @Nullable
    T create();
}