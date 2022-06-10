package org.gradle.plugin.management.internal;

import org.gradle.api.BuildException;

public class InvalidPluginRequestException extends BuildException {
    private final PluginRequestInternal pluginRequest;

    public InvalidPluginRequestException(PluginRequestInternal pluginRequest, String message) {
        super(message);
        this.pluginRequest = pluginRequest;
    }

    public PluginRequestInternal getPluginRequest() {
        return pluginRequest;
    }
}