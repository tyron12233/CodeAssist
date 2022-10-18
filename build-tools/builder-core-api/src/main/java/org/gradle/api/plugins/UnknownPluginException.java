package org.gradle.api.plugins;

import org.gradle.api.InvalidUserDataException;

/**
 * A {@code UnknownPluginException} is thrown when an unknown plugin id is provided. 
 */
public class UnknownPluginException extends InvalidUserDataException {
    public UnknownPluginException(String message) {
        super(message);
    }
}
