package com.tyron.builder.api.internal.typeconversion;

/**
 * Type literal, useful for nested Generics.
 */
public class TypeInfo<T> {
    private final Class<T> targetType;

    public TypeInfo(Class<?> targetType) {
        assert targetType != null;
        //noinspection unchecked
        this.targetType = (Class<T>) targetType;
    }

    public Class<T> getTargetType() {
        return targetType;
    }
}