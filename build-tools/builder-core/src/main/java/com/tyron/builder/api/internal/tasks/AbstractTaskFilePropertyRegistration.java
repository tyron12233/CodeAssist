package com.tyron.builder.api.internal.tasks;

public abstract class AbstractTaskFilePropertyRegistration implements TaskPropertyRegistration {
    private String propertyName;
    private boolean optional;
    private final StaticValue value;

    public AbstractTaskFilePropertyRegistration(StaticValue value) {
        this.value = value;
    }

    @Override
    public String getPropertyName() {
        return propertyName;
    }

    @Override
    public StaticValue getValue() {
        return value;
    }

    @Override
    public boolean isOptional() {
        return optional;
    }

    public void setPropertyName(String propertyName) {
        this.propertyName = TaskPropertyUtils.checkPropertyName(propertyName);
    }

    public void setOptional(boolean optional) {
        this.optional = optional;
    }
}