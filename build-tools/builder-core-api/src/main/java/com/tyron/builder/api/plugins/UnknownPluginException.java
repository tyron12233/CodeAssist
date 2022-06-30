package com.tyron.builder.api.plugins;

import com.tyron.builder.api.InvalidUserDataException;

/**
 * A {@code UnknownPluginException} is thrown when an unknown plugin id is provided. 
 */
public class UnknownPluginException extends InvalidUserDataException {
    public UnknownPluginException(String message) {
        super(message);
    }
}
