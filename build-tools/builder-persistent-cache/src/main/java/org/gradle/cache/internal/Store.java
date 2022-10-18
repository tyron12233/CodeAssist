package org.gradle.cache.internal;

import org.gradle.internal.Factory;

public interface Store<T> {
    T load(Factory<T> createIfNotPresent);
}
