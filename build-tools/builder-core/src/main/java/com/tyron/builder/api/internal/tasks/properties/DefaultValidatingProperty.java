package com.tyron.builder.api.internal.tasks.properties;

public class DefaultValidatingProperty extends AbstractValidatingProperty {
    public DefaultValidatingProperty(String propertyName, PropertyValue value, boolean optional, ValidationAction validationAction) {
        super(propertyName, value, optional, validationAction);
    }
}