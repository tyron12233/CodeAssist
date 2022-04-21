package com.tyron.builder.internal.watch.registry.impl;

import java.util.function.BiFunction;
import java.util.function.BinaryOperator;

/**
 * Utility class to help with using {@link java.util.stream.Stream#reduce(Object, BiFunction, BinaryOperator)}.
 * Promote to a more shared subproject if useful.
 */
public abstract class Combiners {
    private static final BinaryOperator<?> NON_COMBINING = (a, b) -> {
        throw new IllegalStateException("Not a combinable operation");
    };

    /**
     * We know the stream we are processing is handled sequentially, and hence there is no need for a combiner.
     */
    @SuppressWarnings("unchecked")
    public static <T> BinaryOperator<T> nonCombining() {
        return (BinaryOperator<T>) NON_COMBINING;
    }
}
