package com.tyron.builder.api.internal.tasks.properties;

import com.tyron.builder.api.internal.reflect.PropertyMetadata;
import com.tyron.builder.api.internal.reflect.validation.TypeValidationContext;
import com.tyron.builder.api.internal.tasks.properties.annotations.PropertyAnnotationHandler;

import org.jetbrains.annotations.Nullable;

import java.util.Set;

public interface TypeMetadata {
    void visitValidationFailures(@Nullable String ownerPropertyPath, TypeValidationContext validationContext);

    /**
     * Returns the set of relevant properties, that is, those properties annotated with a relevant annotation.
     */
    Set<PropertyMetadata> getPropertiesMetadata();

    boolean hasAnnotatedProperties();

    PropertyAnnotationHandler getAnnotationHandlerFor(PropertyMetadata propertyMetadata);
}

