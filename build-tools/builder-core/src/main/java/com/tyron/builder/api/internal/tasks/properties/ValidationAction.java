package com.tyron.builder.api.internal.tasks.properties;

import com.tyron.builder.api.internal.tasks.TaskValidationContext;

public interface ValidationAction {
    void validate(String propertyName, Object value, TaskValidationContext context);
}