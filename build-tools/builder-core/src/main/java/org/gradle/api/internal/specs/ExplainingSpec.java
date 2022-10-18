package org.gradle.api.internal.specs;

import org.gradle.api.specs.Spec;

/**
 * A predicate against objects of type T that can explain the unsatisfied reason.
 *
 * @param <T> The target type for this Spec
 */
public interface ExplainingSpec<T> extends Spec<T> {

    /**
     * Explains why the spec is not satisfied.
     *
     * @param element candidate
     * @return the description. Must not be null if the spec is not satisfied. Is null if spec is satisfied.
     */
    String whyUnsatisfied(T element);
}
