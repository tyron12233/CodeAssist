package com.tyron.builder.plugin.internal;

import com.tyron.builder.api.BuildException;

public class InvalidPluginIdException extends BuildException {

    private final String reason;

    public InvalidPluginIdException(String pluginId, String reason) {
        super(String.format("plugin id '%s' is invalid: %s", pluginId, reason));
        this.reason = reason;
    }

    public String getReason() {
        return reason;
    }
}
