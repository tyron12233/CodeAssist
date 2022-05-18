package com.tyron.builder.api;

/**
 * <p>An <code>UnknownTaskException</code> is thrown when a task referenced by path cannot be found.</p>
 */
public class UnknownTaskException extends UnknownDomainObjectException {
    public UnknownTaskException(String message) {
        super(message);
    }

    public UnknownTaskException(String message, Throwable cause) {
        super(message, cause);
    }
}