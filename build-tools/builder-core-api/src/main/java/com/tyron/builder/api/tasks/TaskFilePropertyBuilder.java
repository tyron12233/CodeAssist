package com.tyron.builder.api.tasks;

public interface TaskFilePropertyBuilder extends TaskPropertyBuilder {
    /**
     * Sets the name for this property. The name must be a non-empty string.
     *
     * <p>If the method is not called, or if it is called with {@code null}, a name
     * will be assigned to the property automatically.</p>
     */
    TaskFilePropertyBuilder withPropertyName(String propertyName);
}