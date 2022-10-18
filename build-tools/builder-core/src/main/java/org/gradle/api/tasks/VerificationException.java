package org.gradle.api.tasks;

public class VerificationException extends RuntimeException {

    public VerificationException(String message) {
        super(message);
    }
}
