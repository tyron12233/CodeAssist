package com.tyron.builder.api.internal.properties;

import javax.annotation.Nullable;
import java.util.Map;

/**
 * Immutable set of Gradle properties loaded at the start of the build.
 */
public interface GradleProperties {

    @Nullable
    Object find(String propertyName);

    /**
     * Merges the loaded properties with the given properties and returns an immutable
     * map with the result.
     *
     * @param properties read-only properties to be merged with the set of loaded properties.
     */
    Map<String, Object> mergeProperties(Map<String, Object> properties);
}
