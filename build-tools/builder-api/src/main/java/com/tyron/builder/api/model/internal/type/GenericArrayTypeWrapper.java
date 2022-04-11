package com.tyron.builder.api.model.internal.type;

import com.google.common.collect.ImmutableList;

import java.lang.reflect.Array;

class GenericArrayTypeWrapper implements TypeWrapper {
    private final TypeWrapper componentType;
    private final int hashCode;

    public GenericArrayTypeWrapper(TypeWrapper componentType, int hashCode) {
        this.componentType = componentType;
        this.hashCode = hashCode;
    }

    public TypeWrapper getComponentType() {
        return componentType;
    }

    @Override
    public Class<?> getRawClass() {
        // This could probably be more efficient
        return Array.newInstance(componentType.getRawClass(), 0).getClass();
    }

    @Override
    public boolean isAssignableFrom(TypeWrapper wrapper) {
        if (wrapper instanceof GenericArrayTypeWrapper) {
            GenericArrayTypeWrapper arrayType = (GenericArrayTypeWrapper) wrapper;
            return componentType.isAssignableFrom(arrayType.componentType);
        }
        return false;
    }

    @Override
    public void collectClasses(ImmutableList.Builder<Class<?>> builder) {
        componentType.collectClasses(builder);
    }

    @Override
    public boolean equals(Object o) {
        if (o instanceof GenericArrayTypeWrapper) {
            GenericArrayTypeWrapper that = (GenericArrayTypeWrapper) o;
            return this == that || componentType.equals(that.componentType);
        } else {
            return false;
        }
    }

    @Override
    public int hashCode() {
        return hashCode;
    }

    @Override
    public String toString() {
        return getRepresentation(true);
    }

    @Override
    public String getRepresentation(boolean full) {
        return componentType.getRepresentation(full) + "[]";
    }
}