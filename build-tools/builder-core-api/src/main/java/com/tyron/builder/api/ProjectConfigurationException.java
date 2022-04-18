package com.tyron.builder.api;

import com.tyron.builder.api.internal.exceptions.DefaultMultiCauseException;

/**
 * Indicates a problem that occurs during project configuration.
 */
public class ProjectConfigurationException extends DefaultMultiCauseException {
    public ProjectConfigurationException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * Creates an exception with the given message and causes.
     * @param message The message
     * @param causes The causes
     * @since 5.1
     */
    public ProjectConfigurationException(String message, Iterable<? extends Throwable> causes) {
        super(message, causes);
    }
}