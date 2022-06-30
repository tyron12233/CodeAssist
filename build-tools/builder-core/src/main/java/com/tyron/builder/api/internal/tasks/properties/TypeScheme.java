package com.tyron.builder.api.internal.tasks.properties;

/**
 * Metadata about a particular set of types.
 */
public interface TypeScheme {
    TypeMetadataStore getMetadataStore();

    boolean appliesTo(Class<?> type);
}