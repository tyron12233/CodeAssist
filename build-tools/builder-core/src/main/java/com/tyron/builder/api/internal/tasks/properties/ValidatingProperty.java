package com.tyron.builder.api.internal.tasks.properties;

import com.tyron.builder.api.internal.tasks.TaskValidationContext;

public interface ValidatingProperty extends LifecycleAwareValue {
    void validate(TaskValidationContext context);
}