package org.gradle.api;

public class InvalidUserCodeException extends GradleException {
    public InvalidUserCodeException() {
    }

    public InvalidUserCodeException(String message) {
        super(message);
    }

    public InvalidUserCodeException(String message, Throwable cause) {
        super(message, cause);
    }
}