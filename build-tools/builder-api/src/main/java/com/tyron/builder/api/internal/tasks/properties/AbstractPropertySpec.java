package com.tyron.builder.api.internal.tasks.properties;

public abstract class AbstractPropertySpec implements PropertySpec {
    private final String propertyName;

    protected AbstractPropertySpec(String propertyName) {
        this.propertyName = propertyName;
    }

    @Override
    public String getPropertyName() {
        return propertyName;
    }

    @Override
    public int compareTo(PropertySpec o) {
        return getPropertyName().compareTo(o.getPropertyName());
    }
}