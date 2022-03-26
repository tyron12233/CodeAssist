package com.tyron.builder.api.internal.tasks.properties;

public interface ValidatingProperty extends LifecycleAwareValue {
    void validate(TaskValidationContext context);
}