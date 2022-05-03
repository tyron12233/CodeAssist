package com.tyron.builder.api.plugins;

import com.tyron.builder.api.BuildException;

/**
 * Thrown when a plugin is found to be invalid when it is loaded.
 */
public class InvalidPluginException extends BuildException {

    public InvalidPluginException(String message) {
        super(message);
    }

    public InvalidPluginException(String message, Throwable cause) {
        super(message, cause);
    }
}
