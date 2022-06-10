package org.gradle.api.internal.plugins;

import org.gradle.api.Plugin;
import org.gradle.configuration.ConfigurationTargetIdentifier;

import javax.annotation.Nullable;

public interface PluginTarget {

    // Implementations should not wrap exceptions, this is done in DefaultObjectConfigurationAction

    ConfigurationTargetIdentifier getConfigurationTargetIdentifier();

    void applyImperative(@Nullable String pluginId, Plugin<?> plugin);

    void applyRules(@Nullable String pluginId, Class<?> clazz);

    void applyImperativeRulesHybrid(@Nullable String pluginId, Plugin<?> plugin);

}
