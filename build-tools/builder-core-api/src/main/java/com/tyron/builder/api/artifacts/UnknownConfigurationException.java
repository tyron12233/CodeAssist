package com.tyron.builder.api.artifacts;

import com.tyron.builder.api.UnknownDomainObjectException;

/**
 * <p>An {@code UnknownConfigurationException} is thrown when a configuration referenced by name cannot be found.</p>
 */
public class UnknownConfigurationException extends UnknownDomainObjectException {
    public UnknownConfigurationException(String message) {
        super(message);
    }
}
