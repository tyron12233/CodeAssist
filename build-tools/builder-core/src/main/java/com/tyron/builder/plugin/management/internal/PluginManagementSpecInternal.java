package com.tyron.builder.plugin.management.internal;

import com.tyron.builder.api.Action;
import com.tyron.builder.api.initialization.ConfigurableIncludedPluginBuild;
import com.tyron.builder.initialization.IncludedBuildSpec;
import com.tyron.builder.plugin.management.PluginManagementSpec;

import java.util.List;

public interface PluginManagementSpecInternal extends PluginManagementSpec {

    @Override
    PluginResolutionStrategyInternal getResolutionStrategy();

    void includeBuild(String rootProject);

    void includeBuild(String rootProject, Action<ConfigurableIncludedPluginBuild> configuration);

    List<IncludedBuildSpec> getIncludedBuilds();
}
