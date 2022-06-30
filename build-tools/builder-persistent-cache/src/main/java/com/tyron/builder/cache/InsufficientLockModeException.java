package com.tyron.builder.cache;

public class InsufficientLockModeException extends RuntimeException {
    public InsufficientLockModeException(String message) {
        super(message);
    }
}