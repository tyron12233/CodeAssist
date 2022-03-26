package com.tyron.builder.api.tasks;

import com.tyron.builder.api.internal.TaskInternal;
import com.tyron.builder.api.internal.reflect.validation.TypeValidationContext;
import com.tyron.builder.api.internal.tasks.TaskDestroyablesInternal;
import com.tyron.builder.api.internal.tasks.TaskLocalStateInternal;
import com.tyron.builder.api.internal.tasks.properties.PropertyVisitor;
import com.tyron.builder.api.internal.tasks.properties.PropertyWalker;

public class TaskPropertyUtils {
    /**
     * Visits both properties declared via annotations on the properties of the task type as well as
     * properties declared via the runtime API ({@link org.gradle.api.tasks.TaskInputs} etc.).
     */
    public static void visitProperties(PropertyWalker propertyWalker, TaskInternal task, PropertyVisitor visitor) {
        visitProperties(propertyWalker, task, TypeValidationContext.NOOP, visitor);
    }

    /**
     * Visits both properties declared via annotations on the properties of the task type as well as
     * properties declared via the runtime API ({@link org.gradle.api.tasks.TaskInputs} etc.).
     *
     * Reports errors and warnings to the given validation context.
     */
    public static void visitProperties(PropertyWalker propertyWalker, TaskInternal task, TypeValidationContext validationContext, PropertyVisitor visitor) {
        propertyWalker.visitProperties(task, validationContext, visitor);
        task.getInputs().visitRegisteredProperties(visitor);
        task.getOutputs().visitRegisteredProperties(visitor);
        ((TaskDestroyablesInternal) task.getDestroyables()).visitRegisteredProperties(visitor);
        ((TaskLocalStateInternal) task.getLocalState()).visitRegisteredProperties(visitor);
    }

    /**
     * Checks if the given string can be used as a property name.
     *
     * @throws IllegalArgumentException if given name is an empty string.
     */
    public static String checkPropertyName(String propertyName) {
        if (propertyName.isEmpty()) {
            throw new IllegalArgumentException("Property name must not be empty string");
        }
        return propertyName;
    }
}