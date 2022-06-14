package com.tyron.builder.api.internal;

import com.tyron.builder.api.specs.Spec;

public class Specs {
    public static <T> Spec<T> isInstance(final Class<?> type) {
        return new Spec<T>() {
            @Override
            public boolean isSatisfiedBy(T element) {
                return type.isInstance(element);
            }
        };
    }
}
