package org.gradle.plugin.management.internal;

import org.gradle.plugin.management.PluginResolutionStrategy;
import org.gradle.plugin.use.PluginId;

public interface PluginResolutionStrategyInternal extends PluginResolutionStrategy {

    PluginRequestInternal applyTo(PluginRequestInternal pluginRequest);

    void setDefaultPluginVersion(PluginId id, String version);
}
