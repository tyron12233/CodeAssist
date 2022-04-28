package com.tyron.builder.internal.reflect.service;

/**
 * Thrown when there is some validation problem with a service.
 */
public class ServiceValidationException extends ServiceLookupException {
    public ServiceValidationException(String message) {
        super(message);
    }
}