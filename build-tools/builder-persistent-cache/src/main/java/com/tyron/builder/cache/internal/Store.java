package com.tyron.builder.cache.internal;

import com.tyron.builder.internal.Factory;

public interface Store<T> {
    T load(Factory<T> createIfNotPresent);
}
