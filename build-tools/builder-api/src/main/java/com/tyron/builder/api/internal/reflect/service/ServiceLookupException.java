package com.tyron.builder.api.internal.reflect.service;


/**
 * Thrown when there is some failure locating a service.
 */

public class ServiceLookupException extends RuntimeException {
    public ServiceLookupException(String message) {
        super(message);
    }

    public ServiceLookupException(String message, Throwable cause) {
        super(message, cause);
    }
}