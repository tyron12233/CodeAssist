package com.tyron.builder.api.internal;

import com.tyron.builder.internal.reflect.Instantiator;

/**
 * <p>Allows default values for the properties of this object to be declared. Most implementations are generated at
 * run-time from existing classes, by a {@link Instantiator} implementation.</p>
 *
 * <p>Each getter of an {@code IConventionAware} object should use the mappings to determine the value for the property,
 * when no value has been explicitly set for the property.</p>
 */
public interface IConventionAware {

    /**
     * Returns the convention mapping for the properties of this object. The returned mapping object can be used to
     * manage the mapping for individual properties.
     *
     * @return The mapping. Never returns null.
     */
    ConventionMapping getConventionMapping();
}
