package com.tyron.builder.internal.reflect.annotations;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.tyron.builder.internal.reflect.validation.TypeValidationContext;

import java.lang.annotation.Annotation;

public interface TypeAnnotationMetadata {
    /**
     * The annotations present on the type itself.
     */
    ImmutableSet<Annotation> getAnnotations();

    /**
     * Whether an annotation of the given type is present on the type itself.
     */
    boolean isAnnotationPresent(Class<? extends Annotation> annotationType);

    /**
     * Information about the type and annotations of each property of the type.
     */
    ImmutableSortedSet<PropertyAnnotationMetadata> getPropertiesAnnotationMetadata();

    void visitValidationFailures(TypeValidationContext validationContext);
}