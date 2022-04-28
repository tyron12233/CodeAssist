package com.tyron.builder.api;


/**
 * <p>A <code>BuildCancelledException</code> is thrown when a build is interrupted due to cancellation request.
 *
 * @since 2.1
 */
public class BuildCancelledException extends BuildException {
    public BuildCancelledException() {
        this("Build cancelled.");
    }

    public BuildCancelledException(String message) {
        super(message);
    }

    public BuildCancelledException(String message, Throwable cause) {
        super(message, cause);
    }
}
