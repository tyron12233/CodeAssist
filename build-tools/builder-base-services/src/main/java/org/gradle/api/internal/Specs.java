package org.gradle.api.internal;

import org.gradle.api.specs.Spec;

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
