package org.gradle.plugin.internal;

import org.gradle.api.GradleException;

public class InvalidPluginIdException extends GradleException {

    private final String reason;

    public InvalidPluginIdException(String pluginId, String reason) {
        super(String.format("plugin id '%s' is invalid: %s", pluginId, reason));
        this.reason = reason;
    }

    public String getReason() {
        return reason;
    }
}
