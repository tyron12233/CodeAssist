package org.gradle.plugin.internal;

import org.gradle.api.BuildException;

public class InvalidPluginVersionException extends BuildException {

    private final String reason;

    public InvalidPluginVersionException(String pluginVersion, String reason) {
        super(String.format("plugin version '%s' is invalid: %s", pluginVersion, reason));
        this.reason = reason;
    }

    public String getReason() {
        return reason;
    }
}
