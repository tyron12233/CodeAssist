package org.gradle.api.internal.tasks.properties;

import org.gradle.internal.reflect.PropertyMetadata;
import org.gradle.internal.reflect.validation.TypeValidationContext;
import org.gradle.api.internal.tasks.properties.annotations.PropertyAnnotationHandler;

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

