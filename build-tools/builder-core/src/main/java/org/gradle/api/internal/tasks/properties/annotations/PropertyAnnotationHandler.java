package org.gradle.api.internal.tasks.properties.annotations;

import com.google.common.collect.ImmutableSet;
import org.gradle.internal.reflect.AnnotationCategory;
import org.gradle.internal.reflect.PropertyMetadata;
import org.gradle.internal.reflect.validation.TypeValidationContext;
import org.gradle.api.internal.tasks.properties.BeanPropertyContext;
import org.gradle.api.internal.tasks.properties.PropertyValue;
import org.gradle.api.internal.tasks.properties.PropertyVisitor;

import java.lang.annotation.Annotation;

/**
 * Handles validation, dependency handling, and skipping for a property marked with a given annotation.
 *
 * <p>Each handler must be registered as a global service.</p>
 */
public interface PropertyAnnotationHandler {
    /**
     * The annotation type which this handler is responsible for.
     */
    Class<? extends Annotation> getAnnotationType();

    /**
     * The modifier annotations allowed for the handled property type. This set can further be restricted by the actual work type.
     */
    ImmutableSet<? extends AnnotationCategory> getAllowedModifiers();

    /**
     * Does this handler do something useful with the properties that match it? Or can these properties be ignored?
     *
     * Should consider splitting up this type, perhaps into something that inspects the properties and produces the actual handlers and validation problems.
     */
    boolean isPropertyRelevant();

    /**
     * Is the given visitor interested in this annotation?
     */
    boolean shouldVisit(PropertyVisitor visitor);

    /**
     * Visit the value of a property with this annotation attached.
     */
    void visitPropertyValue(String propertyName, PropertyValue value, PropertyMetadata propertyMetadata, PropertyVisitor visitor, BeanPropertyContext context);

    /**
     * Visits problems associated with the given property, if any.
     */
    default void validatePropertyMetadata(PropertyMetadata propertyMetadata, TypeValidationContext validationContext) {}
}