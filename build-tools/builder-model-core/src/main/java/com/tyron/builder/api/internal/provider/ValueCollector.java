package com.tyron.builder.api.internal.provider;

import com.google.common.collect.ImmutableCollection;

import org.jetbrains.annotations.Nullable;

public interface ValueCollector<T> {
    void add(@Nullable T value, ImmutableCollection.Builder<T> dest);

    void addAll(Iterable<? extends T> values, ImmutableCollection.Builder<T> dest);
}