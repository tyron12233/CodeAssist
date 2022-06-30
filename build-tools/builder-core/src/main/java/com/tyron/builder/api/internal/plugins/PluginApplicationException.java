package com.tyron.builder.api.internal.plugins;

import com.tyron.builder.api.BuildException;
import com.tyron.builder.internal.DisplayName;
import com.tyron.builder.internal.exceptions.Contextual;

@Contextual
public class PluginApplicationException extends BuildException {

    public PluginApplicationException(DisplayName pluginDisplayName, Throwable cause) {
        super(String.format("Failed to apply %s.", pluginDisplayName.getDisplayName()), cause);
    }
}
