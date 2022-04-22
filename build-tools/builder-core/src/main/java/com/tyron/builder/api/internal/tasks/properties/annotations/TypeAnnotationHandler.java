package com.tyron.builder.api.internal.tasks.properties.annotations;

import com.tyron.builder.internal.reflect.validation.TypeValidationContext;

import java.lang.annotation.Annotation;

public interface TypeAnnotationHandler {
    /**
     * The annotation type which this handler is responsible for.
     *
     * @return The annotation.
     */
    Class<? extends Annotation> getAnnotationType();

    /**
     * Visits problems associated with the given property, if any.
     */
    void validateTypeMetadata(Class<?> classWithAnnotationAttached, TypeValidationContext visitor);
}