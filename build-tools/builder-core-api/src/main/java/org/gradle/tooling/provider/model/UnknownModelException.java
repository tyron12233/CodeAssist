package org.gradle.tooling.provider.model;

import org.gradle.api.GradleException;

/**
 * Thrown when an unknown tooling model is requested.
 */
public class UnknownModelException extends GradleException {
    public UnknownModelException(String message) {
        super(message);
    }
}

