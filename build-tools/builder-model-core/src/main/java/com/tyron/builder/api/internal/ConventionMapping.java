package com.tyron.builder.api.internal;

import groovy.lang.Closure;

import javax.annotation.Nullable;
import java.util.concurrent.Callable;

/**
 * <p>A {@code ConventionMapping} maintains the convention mappings for the properties of a particular object.</p>
 *
 * <p>Implementations should also allow mappings to be set using dynamic properties.</p>
 */
public interface ConventionMapping {

    MappedProperty map(String propertyName, Closure<?> value);

    MappedProperty map(String propertyName, Callable<?> value);

    /**
     * Mark a property as ineligible for convention mapping.
     */
    void ineligible(String propertyName);

    @Nullable
    <T> T getConventionValue(@Nullable T actualValue, String propertyName, boolean isExplicitValue);

    interface MappedProperty {
        void cache();
    }
}
