package com.tyron.builder.api;

import org.jetbrains.annotations.Nullable;

/**
 * <p><code>BuildException</code> is the base class of all exceptions thrown by the Build API.</p>
 */
public class BuildException extends RuntimeException {
    public BuildException() {
        super();
    }

    public BuildException(String message) {
        super(message);
    }

    public BuildException(String message, @Nullable Throwable cause) {
        super(message, cause);
    }
}