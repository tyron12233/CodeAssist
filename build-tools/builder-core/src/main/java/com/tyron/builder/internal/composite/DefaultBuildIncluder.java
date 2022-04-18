package com.tyron.builder.internal.composite;


import com.tyron.builder.api.internal.BuildDefinition;
import com.tyron.builder.api.internal.GradleInternal;
import com.tyron.builder.api.internal.reflect.DirectInstantiator;
import com.tyron.builder.internal.reflect.Instantiator;
import com.tyron.builder.initialization.IncludedBuildSpec;
import com.tyron.builder.internal.build.BuildIncluder;
import com.tyron.builder.internal.build.BuildStateRegistry;
import com.tyron.builder.internal.build.IncludedBuildState;
import com.tyron.builder.internal.build.PublicBuildPath;
import com.tyron.builder.internal.buildTree.BuildInclusionCoordinator;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

import javax.inject.Inject;

public class DefaultBuildIncluder implements BuildIncluder {

    private final BuildStateRegistry buildRegistry;
    private final BuildInclusionCoordinator coordinator;
    private final PublicBuildPath publicBuildPath;
    private final Instantiator instantiator;
    private final GradleInternal gradle;
    private final List<BuildDefinition> pluginBuildDefinitions = new ArrayList<>();

    @Inject
    public DefaultBuildIncluder(BuildStateRegistry buildRegistry, BuildInclusionCoordinator coordinator, PublicBuildPath publicBuildPath, GradleInternal gradle) {
        this(buildRegistry, coordinator, publicBuildPath, DirectInstantiator.INSTANCE, gradle);
    }

    public DefaultBuildIncluder(BuildStateRegistry buildRegistry, BuildInclusionCoordinator coordinator, PublicBuildPath publicBuildPath, Instantiator instantiator, GradleInternal gradle) {
        this.buildRegistry = buildRegistry;
        this.coordinator = coordinator;
        this.publicBuildPath = publicBuildPath;
        this.instantiator = instantiator;
        this.gradle = gradle;
    }

    @Override
    public IncludedBuildState includeBuild(IncludedBuildSpec includedBuildSpec) {
        BuildDefinition buildDefinition = toBuildDefinition(includedBuildSpec, gradle);
        IncludedBuildState build = buildRegistry.addIncludedBuild(buildDefinition);
        coordinator.prepareForInclusion(build, buildDefinition.isPluginBuild());
        return build;
    }

    @Override
    public void registerPluginBuild(IncludedBuildSpec includedBuildSpec) {
        pluginBuildDefinitions.add(toBuildDefinition(includedBuildSpec, gradle));
    }

    @Override
    public Collection<IncludedBuildState> includeRegisteredPluginBuilds() {
        return pluginBuildDefinitions.stream().map(buildDefinition -> {
            IncludedBuildState build = buildRegistry.addIncludedBuild(buildDefinition);
            coordinator.prepareForInclusion(build, true);
            return build;
        }).collect(Collectors.toList());
    }

    private BuildDefinition toBuildDefinition(IncludedBuildSpec includedBuildSpec, GradleInternal gradle) {
        gradle.getOwner().assertCanAdd(includedBuildSpec);
        return includedBuildSpec.toBuildDefinition(gradle.getStartParameter(), publicBuildPath, instantiator);
    }
}