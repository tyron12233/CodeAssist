package com.tyron.builder.api;

/**
 * <code>UncheckedIOException</code> is used to wrap an {@link java.io.IOException} into an unchecked exception.
 */
public class UncheckedIOException extends RuntimeException {
    public UncheckedIOException() {
    }

    public UncheckedIOException(String message) {
        super(message);
    }

    public UncheckedIOException(String message, Throwable cause) {
        super(message, cause);
    }

    public UncheckedIOException(Throwable cause) {
        super(cause);
    }
}