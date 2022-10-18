package com.tyron.builder.ide.common;

/**
 * An exception thrown when running an external process.
 */
public class ProcessException extends Exception {

    public ProcessException(Throwable throwable) {
        super(throwable);
    }

    public ProcessException(String message, Throwable throwable) {
        super(message, throwable);
    }

    public ProcessException(String message) {
        super(message);
    }
}