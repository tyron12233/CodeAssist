package com.tyron.builder.api.internal.tasks;

import com.tyron.builder.api.tasks.TaskInputPropertyBuilder;

public class DefaultTaskInputPropertyRegistration implements TaskInputPropertyRegistration {

    private final String propertyName;
    private final StaticValue value;
    private boolean optional;

    public DefaultTaskInputPropertyRegistration(String propertyName, StaticValue value) {
        this.propertyName = propertyName;
        this.value = value;
    }

    @Override
    public String getPropertyName() {
        return propertyName;
    }

    @Override
    public boolean isOptional() {
        return optional;
    }

    @Override
    public TaskInputPropertyBuilder optional(boolean optional) {
        this.optional = optional;
        return this;
    }

    @Override
    public StaticValue getValue() {
        return value;
    }

    @Override
    public String toString() {
        return propertyName;
    }
}