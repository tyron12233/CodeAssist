package org.gradle.internal.reflect;


/**
 * Thrown when a requested method cannot be found.
 */
public class NoSuchMethodException extends RuntimeException {
    public NoSuchMethodException(String message) {
        super(message);
    }
}