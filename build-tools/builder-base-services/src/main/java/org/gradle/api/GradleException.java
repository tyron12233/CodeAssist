package org.gradle.api;

import org.jetbrains.annotations.Nullable;

/**
 * <p><code>BuildException</code> is the base class of all exceptions thrown by the Build API.</p>
 */
public class GradleException extends RuntimeException {
    public GradleException() {
        super();
    }

    public GradleException(String message) {
        super(message);
    }

    public GradleException(String message, @Nullable Throwable cause) {
        super(message, cause);
    }
}