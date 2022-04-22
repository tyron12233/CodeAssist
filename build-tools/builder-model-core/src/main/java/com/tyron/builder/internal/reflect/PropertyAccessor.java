package com.tyron.builder.internal.reflect;

public interface PropertyAccessor<T, F> {
    String getName();

    Class<F> getType();

    F getValue(T target);
}