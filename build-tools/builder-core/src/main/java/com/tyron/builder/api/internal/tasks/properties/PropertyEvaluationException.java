package com.tyron.builder.api.internal.tasks.properties;

import com.tyron.builder.api.BuildException;

public class PropertyEvaluationException extends BuildException {

    public PropertyEvaluationException(Object work, String propertyName, Throwable cause) {
        super(String.format("Error while evaluating property '%s' of %s", propertyName, work), cause);
    }
}