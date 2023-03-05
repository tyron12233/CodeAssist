package org.gradle.api.internal.provider;

public class MissingValueException extends IllegalStateException {
    public MissingValueException(String message) {
        super(message);
    }

    public MissingValueException(String message, Throwable cause) {
        super(message, cause);
    }
}