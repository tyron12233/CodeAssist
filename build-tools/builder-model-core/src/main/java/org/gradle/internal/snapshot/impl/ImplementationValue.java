package org.gradle.internal.snapshot.impl;

public class ImplementationValue {
    private final String classIdentifier;
    private final Object value;

    public ImplementationValue(String classIdentifier, Object value) {
        this.classIdentifier = classIdentifier;
        this.value = value;
    }

    public String getImplementationClassIdentifier() {
        return classIdentifier;
    }

    public Object getValue() {
        return value;
    }

    @Override
    public String toString() {
        return "(" + classIdentifier + ") " + value;
    }
}