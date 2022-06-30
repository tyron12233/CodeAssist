package com.tyron.builder.internal.reflect;

/**
 * Thrown when a requested property cannot be found.
 */
public class NoSuchPropertyException extends RuntimeException {
    public NoSuchPropertyException(String message) {
        super(message);
    }
}