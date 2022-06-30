package com.tyron.builder.api.internal.plugins;

import com.tyron.builder.api.plugins.PluginAware;
import com.tyron.builder.configuration.ConfigurationTargetIdentifier;

public interface PluginAwareInternal extends PluginAware {
    @Override
    PluginManagerInternal getPluginManager();

    ConfigurationTargetIdentifier getConfigurationTargetIdentifier();
}
