package org.gradle.api.internal.plugins;

import org.gradle.api.GradleException;
import org.gradle.internal.DisplayName;
import org.gradle.internal.exceptions.Contextual;

@Contextual
public class PluginApplicationException extends GradleException {

    public PluginApplicationException(DisplayName pluginDisplayName, Throwable cause) {
        super(String.format("Failed to apply %s.", pluginDisplayName.getDisplayName()), cause);
    }
}
