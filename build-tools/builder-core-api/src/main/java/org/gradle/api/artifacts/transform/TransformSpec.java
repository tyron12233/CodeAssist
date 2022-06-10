package org.gradle.api.artifacts.transform;

import org.gradle.api.Action;
import org.gradle.api.attributes.AttributeContainer;

/**
 * Base configuration for artifact transform registrations.
 *
 * @see org.gradle.api.artifacts.dsl.DependencyHandler#registerTransform(Class, Action)
 * @param <T> The transform specific parameter type.
 * @since 5.3
 */
public interface TransformSpec<T extends TransformParameters> {
    /**
     * Attributes that match the variant that is consumed.
     *
     * @see org.gradle.api.artifacts.dsl.DependencyHandler#registerTransform(Class, Action)
     */
    AttributeContainer getFrom();

    /**
     * Attributes that match the variant that is produced.
     *
     * @see org.gradle.api.artifacts.dsl.DependencyHandler#registerTransform(Class, Action)
     */
    AttributeContainer getTo();

    /**
     * The parameters for the transform action.
     *
     * @see org.gradle.api.artifacts.dsl.DependencyHandler#registerTransform(Class, Action)
     */
    T getParameters();

    /**
     * Configure the parameters for the transform action.
     * 
     * @see org.gradle.api.artifacts.dsl.DependencyHandler#registerTransform(Class, Action)
     */
    void parameters(Action<? super T> action);
}
