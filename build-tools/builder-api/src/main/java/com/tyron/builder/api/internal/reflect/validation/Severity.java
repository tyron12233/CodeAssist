package com.tyron.builder.api.internal.reflect.validation;

public enum Severity {
    /**
     * A validation warning, emitted as a deprecation warning during runtime.
     */
    WARNING("Warning"),

    /**
     * A validation error, emitted as a failure cause during runtime.
     */
    ERROR("Error");

    private final String displayName;

    Severity(String displayName) {
        this.displayName = displayName;
    }

    @Override
    public String toString() {
        return displayName;
    }

    public boolean isWarning() {
        return this != ERROR;
    }
}