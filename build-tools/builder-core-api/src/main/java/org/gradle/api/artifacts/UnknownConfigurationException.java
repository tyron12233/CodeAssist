package org.gradle.api.artifacts;

import org.gradle.api.UnknownDomainObjectException;

/**
 * <p>An {@code UnknownConfigurationException} is thrown when a configuration referenced by name cannot be found.</p>
 */
public class UnknownConfigurationException extends UnknownDomainObjectException {
    public UnknownConfigurationException(String message) {
        super(message);
    }
}
