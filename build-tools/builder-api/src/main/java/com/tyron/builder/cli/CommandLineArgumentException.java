package com.tyron.builder.cli;

/**
 * A {@code CommandLineArgumentException} is thrown when command-line arguments cannot be parsed.
 */
public class CommandLineArgumentException extends RuntimeException {
    public CommandLineArgumentException(String message) {
        super(message);
    }

    public CommandLineArgumentException(String message, Throwable cause) {
        super(message, cause);
    }
}

