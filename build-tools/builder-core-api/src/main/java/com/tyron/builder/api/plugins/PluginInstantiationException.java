package com.tyron.builder.api.plugins;

import com.tyron.builder.api.BuildException;
import com.tyron.builder.internal.exceptions.Contextual;

/**
 * A {@code PluginInstantiationException} is thrown when a plugin cannot be instantiated.
 */
@Contextual
public class PluginInstantiationException extends BuildException {
    public PluginInstantiationException(String message) {
        super(message);
    }

    public PluginInstantiationException(String message, Throwable cause) {
        super(message, cause);
    }
}
