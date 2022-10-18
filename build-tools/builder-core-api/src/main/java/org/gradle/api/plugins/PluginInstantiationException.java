package org.gradle.api.plugins;

import org.gradle.api.GradleException;
import org.gradle.internal.exceptions.Contextual;

/**
 * A {@code PluginInstantiationException} is thrown when a plugin cannot be instantiated.
 */
@Contextual
public class PluginInstantiationException extends GradleException {
    public PluginInstantiationException(String message) {
        super(message);
    }

    public PluginInstantiationException(String message, Throwable cause) {
        super(message, cause);
    }
}
