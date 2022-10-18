package org.gradle.model.internal.core;

public interface NamedEntityInstantiator<T> {
    <S extends T> S create(String name, Class<S> type);
}