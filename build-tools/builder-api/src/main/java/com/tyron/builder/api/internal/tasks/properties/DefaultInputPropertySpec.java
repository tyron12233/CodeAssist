package com.tyron.builder.api.internal.tasks.properties;

public class DefaultInputPropertySpec extends AbstractPropertySpec implements InputPropertySpec {
    private final PropertyValue value;

    public DefaultInputPropertySpec(String propertyName, PropertyValue value) {
        super(propertyName);
        this.value = value;
    }

    @Override
    public PropertyValue getValue() {
        return value;
    }

    @Override
    public String toString() {
        return getPropertyName();
    }
}