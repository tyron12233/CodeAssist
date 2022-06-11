package org.gradle.tooling.provider.model;

import org.gradle.api.BuildException;

/**
 * Thrown when an unknown tooling model is requested.
 */
public class UnknownModelException extends BuildException {
    public UnknownModelException(String message) {
        super(message);
    }
}

