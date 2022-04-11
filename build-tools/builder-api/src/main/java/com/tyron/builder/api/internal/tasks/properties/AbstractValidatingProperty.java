package com.tyron.builder.api.internal.tasks.properties;

import com.tyron.builder.api.internal.DeferredUtil;
import com.tyron.builder.api.internal.reflect.problems.ValidationProblemId;
import com.tyron.builder.api.internal.reflect.validation.Severity;
import com.tyron.builder.api.internal.reflect.validation.TypeValidationContext;

public abstract class AbstractValidatingProperty implements ValidatingProperty {
    private final String propertyName;
    private final PropertyValue value;
    private final boolean optional;
    private final ValidationAction validationAction;

    public AbstractValidatingProperty(String propertyName, PropertyValue value, boolean optional, ValidationAction validationAction) {
        this.propertyName = propertyName;
        this.value = value;
        this.optional = optional;
        this.validationAction = validationAction;
    }

    public static void reportValueNotSet(String propertyName, TypeValidationContext context) {
        context.visitPropertyProblem(problem -> {
            problem.withId(ValidationProblemId.VALUE_NOT_SET)
                    .reportAs(Severity.ERROR)
                    .forProperty(propertyName)
                    .withDescription("doesn't have a configured value")
                    .happensBecause("This property isn't marked as optional and no value has been configured")
                    .addPossibleSolution(() -> "Assign a value to '" + propertyName + "'")
                    .addPossibleSolution(() -> "Mark property '" + propertyName + "' as optional")
                    .documentedAt("validation_problems", "value_not_set");
        });
    }

    @Override
    public void validate(TaskValidationContext context) {
        Object unpacked = DeferredUtil.unpackOrNull(value.call());
        if (unpacked == null) {
            if (!optional) {
                reportValueNotSet(propertyName, context);
            }
        } else {
            validationAction.validate(propertyName, unpacked, context);
        }
    }

    @Override
    public void prepareValue() {
        value.maybeFinalizeValue();
    }

    @Override
    public void cleanupValue() {
    }
}
