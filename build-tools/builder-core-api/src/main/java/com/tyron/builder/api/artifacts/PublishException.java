package com.tyron.builder.api.artifacts;

import com.tyron.builder.api.BuildException;
import com.tyron.builder.internal.exceptions.Contextual;

/**
 * <p>A <code>PublishException</code> is thrown when a dependency configuration cannot be published for some reason.</p>
 */
@Contextual
public class PublishException extends BuildException {
    public PublishException(String message, Throwable cause) {
        super(message, cause);
    }

    public PublishException(String message) {
        super(message);
    }
}
