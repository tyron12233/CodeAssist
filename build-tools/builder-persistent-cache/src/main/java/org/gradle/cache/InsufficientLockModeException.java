package org.gradle.cache;

public class InsufficientLockModeException extends RuntimeException {
    public InsufficientLockModeException(String message) {
        super(message);
    }
}