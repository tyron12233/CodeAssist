package com.tyron.builder.api.internal.reflect.service;

/**
 * Thrown when a service instance cannot be created.
 */
public class ServiceCreationException extends ServiceLookupException {
    public ServiceCreationException(String message) {
        super(message);
    }

    public ServiceCreationException(String message, Throwable cause) {
        super(message, cause);
    }
}