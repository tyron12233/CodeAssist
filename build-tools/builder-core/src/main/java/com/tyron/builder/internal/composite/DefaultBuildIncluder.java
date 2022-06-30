package com.tyron.builder.internal.composite;



import com.tyron.builder.api.internal.BuildDefinition;
import com.tyron.builder.api.internal.GradleInternal;
import com.tyron.builder.initialization.IncludedBuildSpec;
import com.tyron.builder.internal.build.BuildIncluder;
import com.tyron.builder.internal.build.BuildStateRegistry;
import com.tyron.builder.internal.build.IncludedBuildState;
import com.tyron.builder.internal.build.PublicBuildPath;
import com.tyron.builder.internal.reflect.Instantiator;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

public class DefaultBuildIncluder implements BuildIncluder {

    private final BuildStateRegistry buildRegistry;
    private final PublicBuildPath publicBuildPath;
    private final Instantiator instantiator;
    private final List<BuildDefinition> pluginBuildDefinitions = new ArrayList<>();

    public DefaultBuildIncluder(BuildStateRegistry buildRegistry, PublicBuildPath publicBuildPath, Instantiator instantiator) {
        this.buildRegistry = buildRegistry;
        this.publicBuildPath = publicBuildPath;
        this.instantiator = instantiator;
    }

    @Override
    public IncludedBuildState includeBuild(IncludedBuildSpec includedBuildSpec, GradleInternal gradle) {
        return buildRegistry.addIncludedBuild(toBuildDefinition(includedBuildSpec, gradle));
    }

    @Override
    public void registerPluginBuild(IncludedBuildSpec includedBuildSpec, GradleInternal gradle) {
        pluginBuildDefinitions.add(toBuildDefinition(includedBuildSpec, gradle));
    }

    @Override
    public Collection<IncludedBuildState> includeRegisteredPluginBuilds() {
        return pluginBuildDefinitions.stream().map(buildRegistry::addIncludedBuild).collect(Collectors.toList());
    }

    private BuildDefinition toBuildDefinition(IncludedBuildSpec includedBuildSpec, GradleInternal gradle) {
        gradle.getOwner().assertCanAdd(includedBuildSpec);
        return includedBuildSpec.toBuildDefinition(gradle.getStartParameter(), publicBuildPath, instantiator);
    }
}
