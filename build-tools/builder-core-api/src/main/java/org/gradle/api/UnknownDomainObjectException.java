package org.gradle.api;

/**
 * <p>A {@code UnknownDomainObjectException} is the super class of all exceptions thrown when a given domain object
 * cannot be located.</p>
 */
public class UnknownDomainObjectException extends GradleException {
    public UnknownDomainObjectException(String message) {
        super(message);
    }

    public UnknownDomainObjectException(String message, Throwable cause) {
        super(message, cause);
    }
}

