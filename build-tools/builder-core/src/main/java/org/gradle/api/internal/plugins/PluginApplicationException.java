package org.gradle.api.internal.plugins;

import org.gradle.api.BuildException;
import org.gradle.internal.DisplayName;
import org.gradle.internal.exceptions.Contextual;

@Contextual
public class PluginApplicationException extends BuildException {

    public PluginApplicationException(DisplayName pluginDisplayName, Throwable cause) {
        super(String.format("Failed to apply %s.", pluginDisplayName.getDisplayName()), cause);
    }
}
