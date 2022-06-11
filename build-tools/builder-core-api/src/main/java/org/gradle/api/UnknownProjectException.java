package org.gradle.api;

/**
 * <p>An <code>UnknownProjectException</code> is thrown when a project referenced by path cannot be found.</p>
 */
public class UnknownProjectException extends GradleException {
    public UnknownProjectException(String message) {
        super(message);
    }

    public UnknownProjectException(String message, Throwable cause) {
        super(message, cause);
    }
}