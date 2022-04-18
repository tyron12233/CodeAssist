package com.tyron.builder.api.internal.tasks.properties;

import com.tyron.builder.api.internal.reflect.validation.TypeValidationContext;

/**
 * Walks properties declared by the type.
 */
public interface PropertyWalker {
    void visitProperties(Object instance, TypeValidationContext validationContext, PropertyVisitor visitor);
}