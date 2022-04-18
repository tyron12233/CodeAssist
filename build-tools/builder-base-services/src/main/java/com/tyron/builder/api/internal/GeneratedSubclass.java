package com.tyron.builder.api.internal;

/**
 * Interface for types that are runtime generated subclasses of some other type.
 */
public interface GeneratedSubclass {
    /**
     * Returns the type from which this type was generated.
     */
    Class<?> publicType();
}