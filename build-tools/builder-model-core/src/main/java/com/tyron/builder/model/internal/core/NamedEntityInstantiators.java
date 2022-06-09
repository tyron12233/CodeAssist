package com.tyron.builder.model.internal.core;

public class NamedEntityInstantiators {
    public static <S> NamedEntityInstantiator<S> nonSubtype(Class<S> nonSubtype, final Class<?> baseClass) {
        return new NamedEntityInstantiator<S>() {
            @Override
            public <D extends S> D create(String name, Class<D> type) {
                throw new IllegalArgumentException(String.format("Cannot create an item of type %s as this is not a subtype of %s.", type.getName(), baseClass.getName()));
            }
        };
    }
}
