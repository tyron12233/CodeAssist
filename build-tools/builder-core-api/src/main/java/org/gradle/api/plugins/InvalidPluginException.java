package org.gradle.api.plugins;

import org.gradle.api.GradleException;

/**
 * Thrown when a plugin is found to be invalid when it is loaded.
 */
public class InvalidPluginException extends GradleException {

    public InvalidPluginException(String message) {
        super(message);
    }

    public InvalidPluginException(String message, Throwable cause) {
        super(message, cause);
    }
}
