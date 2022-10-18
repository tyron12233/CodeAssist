package org.gradle.plugin.management.internal;

import org.gradle.api.Action;
import org.gradle.api.initialization.ConfigurableIncludedPluginBuild;
import org.gradle.initialization.IncludedBuildSpec;
import org.gradle.plugin.management.PluginManagementSpec;

import java.util.List;

public interface PluginManagementSpecInternal extends PluginManagementSpec {

    @Override
    PluginResolutionStrategyInternal getResolutionStrategy();

    void includeBuild(String rootProject);

    void includeBuild(String rootProject, Action<ConfigurableIncludedPluginBuild> configuration);

    List<IncludedBuildSpec> getIncludedBuilds();
}
