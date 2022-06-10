package org.gradle.api.internal.tasks.properties;

import org.gradle.internal.reflect.validation.TypeValidationContext;

/**
 * Walks properties declared by the type.
 */
public interface PropertyWalker {
    void visitProperties(Object instance, TypeValidationContext validationContext, PropertyVisitor visitor);
}