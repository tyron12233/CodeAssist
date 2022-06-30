package com.tyron.builder.internal.service;


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