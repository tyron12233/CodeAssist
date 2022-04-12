package com.tyron.builder.api.resources;

import java.net.URI;

/**
 * Exception thrown when the resource does not exist
 */
public class MissingResourceException extends ResourceException {
    public MissingResourceException(String message) {
        super(message);
    }

    public MissingResourceException(String message, Throwable cause) {
        super(message, cause);
    }

    public MissingResourceException(URI location, String message) {
        super(location, message);
    }

    public MissingResourceException(URI location, String message, Throwable cause) {
        super(location, message, cause);
    }
}