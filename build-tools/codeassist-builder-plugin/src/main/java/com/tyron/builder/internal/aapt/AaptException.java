package com.tyron.builder.internal.aapt;

/**
 * Exception thrown when there is a problem using {@code aapt}.
 */
public class AaptException extends Exception {

    /**
     * Creates a new exception.
     *
     * @param message the exception's message
     */
    public AaptException(String message) {
        super(message);
    }

    /**
     * Creates a new exception.
     *
     * @param message the exception's message
     * @param cause the cause of this exception
     */
    public AaptException(String message, Throwable cause) {
        super(message, cause);
    }
}