package com.tyron.builder.api.artifacts.transform;

import com.tyron.builder.api.Action;
import com.tyron.builder.api.attributes.AttributeContainer;

/**
 * Base configuration for artifact transform registrations.
 *
 * @see com.tyron.builder.api.artifacts.dsl.DependencyHandler#registerTransform(Class, Action)
 * @param <T> The transform specific parameter type.
 * @since 5.3
 */
public interface TransformSpec<T extends TransformParameters> {
    /**
     * Attributes that match the variant that is consumed.
     *
     * @see com.tyron.builder.api.artifacts.dsl.DependencyHandler#registerTransform(Class, Action)
     */
    AttributeContainer getFrom();

    /**
     * Attributes that match the variant that is produced.
     *
     * @see com.tyron.builder.api.artifacts.dsl.DependencyHandler#registerTransform(Class, Action)
     */
    AttributeContainer getTo();

    /**
     * The parameters for the transform action.
     *
     * @see com.tyron.builder.api.artifacts.dsl.DependencyHandler#registerTransform(Class, Action)
     */
    T getParameters();

    /**
     * Configure the parameters for the transform action.
     * 
     * @see com.tyron.builder.api.artifacts.dsl.DependencyHandler#registerTransform(Class, Action)
     */
    void parameters(Action<? super T> action);
}
