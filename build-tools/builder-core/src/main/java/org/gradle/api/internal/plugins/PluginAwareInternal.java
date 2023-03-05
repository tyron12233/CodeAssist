package org.gradle.api.internal.plugins;

import org.gradle.api.plugins.PluginAware;
import org.gradle.configuration.ConfigurationTargetIdentifier;

public interface PluginAwareInternal extends PluginAware {
    @Override
    PluginManagerInternal getPluginManager();

    ConfigurationTargetIdentifier getConfigurationTargetIdentifier();
}
