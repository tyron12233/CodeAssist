package org.gradle.api.internal.tasks.properties;

import org.gradle.api.GradleException;

public class PropertyEvaluationException extends GradleException {

    public PropertyEvaluationException(Object work, String propertyName, Throwable cause) {
        super(String.format("Error while evaluating property '%s' of %s", propertyName, work), cause);
    }
}