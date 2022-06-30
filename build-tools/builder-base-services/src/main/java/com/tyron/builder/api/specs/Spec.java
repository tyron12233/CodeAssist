package com.tyron.builder.api.specs;

import java.util.function.Predicate;

/**
 * Represents some predicate against objects of type T.
 *
 * @param <T> The target type for this Spec
 */
public interface Spec<T> extends Predicate<T> {
    boolean isSatisfiedBy(T element);

    @Override
    default boolean test(T element) {
        return isSatisfiedBy(element);
    }
}
