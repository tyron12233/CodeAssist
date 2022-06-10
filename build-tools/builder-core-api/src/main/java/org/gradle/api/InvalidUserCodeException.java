package org.gradle.api;

public class InvalidUserCodeException extends BuildException {
    public InvalidUserCodeException() {
    }

    public InvalidUserCodeException(String message) {
        super(message);
    }

    public InvalidUserCodeException(String message, Throwable cause) {
        super(message, cause);
    }
}