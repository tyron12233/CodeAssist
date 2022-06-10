package org.gradle.api.artifacts;

import org.gradle.api.UnknownDomainObjectException;

/**
 * An {@code UnknownRepositoryException} is thrown when a repository referenced by name cannot be found.
 */
public class UnknownRepositoryException extends UnknownDomainObjectException {
    public UnknownRepositoryException(String message) {
        super(message);
    }
}
