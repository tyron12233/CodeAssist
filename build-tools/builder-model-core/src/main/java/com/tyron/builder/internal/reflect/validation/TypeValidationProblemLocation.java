package com.tyron.builder.internal.reflect.validation;

import com.tyron.builder.plugin.use.PluginId;

import org.jetbrains.annotations.Nullable;

import java.util.Optional;

public class TypeValidationProblemLocation implements Location {
    private final Class<?> type;
    private final PluginId pluginId;
    private final String parentPropertyName;
    private final String propertyName;

    private TypeValidationProblemLocation(@Nullable Class<?> type, @Nullable PluginId pluginId, @Nullable String parentProperty, @Nullable String property) {
        this.type = type;
        this.pluginId = pluginId;
        this.parentPropertyName = parentProperty;
        this.propertyName = property;
    }

    public static TypeValidationProblemLocation irrelevant() {
        return new TypeValidationProblemLocation(null, null, null, null);
    }

    public static TypeValidationProblemLocation inType(Class<?> type, @Nullable PluginId pluginId) {
        return new TypeValidationProblemLocation(type, pluginId, null, null);
    }

    public static TypeValidationProblemLocation forProperty(@Nullable Class<?> rootType, @Nullable PluginId pluginId, @Nullable String parentProperty, @Nullable String property) {
        return new TypeValidationProblemLocation(rootType, pluginId, parentProperty, property);
    }

    public Optional<Class<?>> getType() {
        return Optional.ofNullable(type);
    }

    public Optional<String> getParentPropertyName() {
        return Optional.ofNullable(parentPropertyName);
    }

    public Optional<String> getPropertyName() {
        return Optional.ofNullable(propertyName);
    }

    public Optional<PluginId> getPlugin() {
        return Optional.ofNullable(pluginId);
    }
}