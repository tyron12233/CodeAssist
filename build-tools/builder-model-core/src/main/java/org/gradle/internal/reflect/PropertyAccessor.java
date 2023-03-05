package org.gradle.internal.reflect;

public interface PropertyAccessor<T, F> {
    String getName();

    Class<F> getType();

    F getValue(T target);
}