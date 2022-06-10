package org.gradle.api.internal.tasks.properties;

import org.gradle.api.internal.tasks.TaskValidationContext;

public interface ValidatingProperty extends LifecycleAwareValue {
    void validate(TaskValidationContext context);
}