package com.tyron.builder.plugin.management.internal;

import com.tyron.builder.plugin.management.PluginResolutionStrategy;
import com.tyron.builder.plugin.use.PluginId;

public interface PluginResolutionStrategyInternal extends PluginResolutionStrategy {

    PluginRequestInternal applyTo(PluginRequestInternal pluginRequest);

    void setDefaultPluginVersion(PluginId id, String version);
}
