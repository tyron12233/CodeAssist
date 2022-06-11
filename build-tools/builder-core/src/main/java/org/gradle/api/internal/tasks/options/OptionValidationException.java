package org.gradle.api.internal.tasks.options;

import org.gradle.api.GradleException;

/*
 * Thrown when there is some problem with an option definition (but not when there is some problem with an option value).
 */
public class OptionValidationException extends GradleException {
    public OptionValidationException(String message) {
        super(message);
    }
}
