package com.tyron.builder.internal.reflect.validation;

import com.tyron.builder.api.internal.DocumentationRegistry;
import com.tyron.builder.plugin.use.PluginId;

import org.jetbrains.annotations.Nullable;

public class DefaultPropertyValidationProblemBuilder extends AbstractValidationProblemBuilder<PropertyProblemBuilder> implements PropertyProblemBuilderInternal {
    private Class<?> rootType;
    private String parentProperty;
    private String property;

    public DefaultPropertyValidationProblemBuilder(DocumentationRegistry documentationRegistry, @Nullable PluginId pluginId) {
        super(documentationRegistry, pluginId);
    }

    @Override
    public PropertyProblemBuilder forProperty(String parentProperty, String property) {
        this.parentProperty = parentProperty;
        this.property = property;
        return this;
    }

    @Override
    public PropertyProblemBuilder forOwner(@Nullable String parentProperty) {
        if (parentProperty == null) {
            return this;
        }
        if (property == null) {
            throw new IllegalStateException("Calling this method doesn't make sense if the property isn't set");
        }
        if (this.parentProperty == null) {
            this.parentProperty = parentProperty;
        } else {
            this.parentProperty = parentProperty + "." + this.parentProperty;
        }
        return this;
    }

    @Override
    public PropertyProblemBuilder forType(@Nullable Class<?> rootType) {
        this.rootType = rootType;
        return this;
    }

    public TypeValidationProblem build() {
        if (problemId == null) {
            throw new IllegalStateException("You must set the problem id");
        }
        if (shortProblemDescription == null) {
            throw new IllegalStateException("You must provide at least a short description of the problem");
        }
        return new TypeValidationProblem(
                problemId,
                severity,
                TypeValidationProblemLocation.forProperty(typeIrrelevantInErrorMessage ? null : rootType, typeIrrelevantInErrorMessage ? null : pluginId, parentProperty, property),
                shortProblemDescription,
                longDescription,
                reason,
                cacheabilityProblemOnly,
                userManualReference,
                possibleSolutions
        );
    }
}