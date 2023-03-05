package org.gradle.api.internal.tasks.properties;

import org.gradle.api.internal.tasks.TaskValidationContext;

public interface ValidationAction {
    void validate(String propertyName, Object value, TaskValidationContext context);
}