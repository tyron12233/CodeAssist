package com.tyron.builder.internal.reflect;


import javax.annotation.Nullable;

public interface PropertyMutator {
    String getName();

    Class<?> getType();

    void setValue(Object target, @Nullable Object value);
}