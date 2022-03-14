package com.tyron.viewbinding.tool.util;

/**
 * Thrown when logging an error message to break out of current execution.
 */
public class LoggedErrorException extends RuntimeException {
    public LoggedErrorException(String message) {
        super(message);
    }
}