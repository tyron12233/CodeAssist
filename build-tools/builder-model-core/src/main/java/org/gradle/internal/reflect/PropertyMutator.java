package org.gradle.internal.reflect;


import javax.annotation.Nullable;

public interface PropertyMutator {
    String getName();

    Class<?> getType();

    void setValue(Object target, @Nullable Object value);
}