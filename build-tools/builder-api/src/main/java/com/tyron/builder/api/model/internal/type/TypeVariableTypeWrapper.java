package com.tyron.builder.api.model.internal.type;

import com.google.common.base.Objects;
import com.google.common.collect.ImmutableList;

import org.jetbrains.annotations.Nullable;

import java.util.Arrays;

/**
 * Wrapper for a {@link java.lang.reflect.TypeVariable}.
 */
class TypeVariableTypeWrapper implements WildcardWrapper {
    private final String name;
    private final TypeWrapper[] bounds;
    private final int hashCode;

    public TypeVariableTypeWrapper(String name, TypeWrapper[] bounds, int hashCode) {
        this.name = name;
        this.bounds = bounds;
        this.hashCode = hashCode;
    }

    @Override
    public Class<?> getRawClass() {
        if (bounds.length > 0) {
            return bounds[0].getRawClass();
        } else {
            return Object.class;
        }
    }

    @Override
    public boolean isAssignableFrom(TypeWrapper wrapper) {
        return false;
    }

    @Override
    public void collectClasses(ImmutableList.Builder<Class<?>> builder) {
        for (TypeWrapper bound : bounds) {
            bound.collectClasses(builder);
        }
    }

    @Override
    public String getRepresentation(boolean full) {
        return name;
    }

    public String getName() {
        return name;
    }

    @Override
    public TypeWrapper getUpperBound() {
        return bounds[0];
    }

    @Nullable
    @Override
    public TypeWrapper getLowerBound() {
        return null;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof TypeVariableTypeWrapper)) {
            return false;
        } else {
            TypeVariableTypeWrapper var2 = (TypeVariableTypeWrapper) o;
            return Objects.equal(this.getName(), var2.getName())
                   && Arrays.equals(this.bounds, var2.bounds);
        }
    }

    @Override
    public int hashCode() {
        return hashCode;
    }
}