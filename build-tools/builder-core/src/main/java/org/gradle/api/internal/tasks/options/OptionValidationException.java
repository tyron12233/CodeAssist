package org.gradle.api.internal.tasks.options;

import org.gradle.api.BuildException;

/*
 * Thrown when there is some problem with an option definition (but not when there is some problem with an option value).
 */
public class OptionValidationException extends BuildException {
    public OptionValidationException(String message) {
        super(message);
    }
}
