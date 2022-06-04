package com.tyron.builder.api.java.archives;

import com.tyron.builder.api.BuildException;

/**
 * Is thrown in the case an operation is applied against a {@link com.tyron.builder.api.java.archives.Manifest} that violates
 * the Manifest specification.
 */
public class ManifestException extends BuildException {
    public ManifestException(String message) {
        super(message);
    }

    public ManifestException(String message, Throwable cause) {
        super(message, cause);
    }
}