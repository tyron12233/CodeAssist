package com.tyron.builder.model.internal.core;

public interface NamedEntityInstantiator<T> {
    <S extends T> S create(String name, Class<S> type);
}