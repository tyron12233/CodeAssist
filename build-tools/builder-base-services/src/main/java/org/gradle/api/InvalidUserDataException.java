package org.gradle.api;

/**
 * A <code>InvalidUserDataException</code> is thrown, if a user is providing illegal data for the build.
 */
public class InvalidUserDataException extends GradleException {
    public InvalidUserDataException() {
    }

    public InvalidUserDataException(String message) {
        super(message);
    }

    public InvalidUserDataException(String message, Throwable cause) {
        super(message, cause);
    }
}