package com.tyron.builder.initialization;

import com.google.common.collect.ImmutableMap;
import com.tyron.builder.api.internal.properties.GradleProperties;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;

class DefaultGradleProperties implements GradleProperties {
    private final Map<String, Object> defaultProperties;
    private final Map<String, Object> overrideProperties;
    private final ImmutableMap<String, Object> gradleProperties;

    public DefaultGradleProperties(
            Map<String, Object> defaultProperties,
            Map<String, Object> overrideProperties
    ) {
        this.defaultProperties = defaultProperties;
        this.overrideProperties = overrideProperties;
        gradleProperties = immutablePropertiesWith(ImmutableMap.of());
    }

    @Nullable
    @Override
    public Object find(String propertyName) {
        return gradleProperties.get(propertyName);
    }

    @Override
    public Map<String, Object> mergeProperties(Map<String, Object> properties) {
        return properties.isEmpty()
                ? gradleProperties
                : immutablePropertiesWith(properties);
    }

    ImmutableMap<String, Object> immutablePropertiesWith(Map<String, Object> properties) {
        return ImmutableMap.copyOf(mergePropertiesWith(properties));
    }

    Map<String, Object> mergePropertiesWith(Map<String, Object> properties) {
        Map<String, Object> result = new HashMap<>();
        result.putAll(defaultProperties);
        result.putAll(properties);
        result.putAll(overrideProperties);
        return result;
    }
}

