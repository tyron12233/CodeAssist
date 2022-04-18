package com.tyron.builder.api.internal.tasks.properties;

public interface ValidationAction {
    void validate(String propertyName, Object value, TaskValidationContext context);
}