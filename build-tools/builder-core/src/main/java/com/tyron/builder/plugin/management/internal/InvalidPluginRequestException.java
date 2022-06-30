package com.tyron.builder.plugin.management.internal;

import com.tyron.builder.api.BuildException;

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