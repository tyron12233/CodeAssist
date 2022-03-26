package com.tyron.builder.api.model.internal.type;

import com.google.common.collect.ImmutableList;

interface TypeWrapper {
    void collectClasses(ImmutableList.Builder<Class<?>> builder);

    String getRepresentation(boolean full);

    Class<?> getRawClass();

    /**
     * Is this type assignable from the given type?
     */
    boolean isAssignableFrom(TypeWrapper wrapper);
}