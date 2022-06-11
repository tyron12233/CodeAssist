package org.gradle.plugin.management.internal;

import org.gradle.api.GradleException;

public class InvalidPluginRequestException extends GradleException {
    private final PluginRequestInternal pluginRequest;

    public InvalidPluginRequestException(PluginRequestInternal pluginRequest, String message) {
        super(message);
        this.pluginRequest = pluginRequest;
    }

    public PluginRequestInternal getPluginRequest() {
        return pluginRequest;
    }
}