package com.tyron.builder.api.internal;

import com.tyron.builder.api.Action;
import com.tyron.builder.StartParameter;
import com.tyron.builder.api.artifacts.DependencySubstitutions;
import com.tyron.builder.internal.Actions;
import com.tyron.builder.internal.build.PublicBuildPath;
import com.tyron.builder.plugin.management.internal.PluginRequests;

import javax.annotation.Nullable;
import java.io.File;

public class BuildDefinition {
    @Nullable
    private final String name;
    @Nullable
    private final File buildRootDir;
    private final StartParameterInternal startParameter;
    private final PluginRequests injectedSettingsPlugins;
    private final Action<? super DependencySubstitutions> dependencySubstitutions;
    private final PublicBuildPath fromBuild;
    private final boolean pluginBuild;

    private BuildDefinition(
            @Nullable String name,
            @Nullable File buildRootDir,
            StartParameterInternal startParameter,
            PluginRequests injectedSettingsPlugins,
            Action<? super DependencySubstitutions> dependencySubstitutions,
            PublicBuildPath fromBuild,
            boolean pluginBuild) {
        this.name = name;
        this.buildRootDir = buildRootDir;
        this.startParameter = startParameter;
        this.injectedSettingsPlugins = injectedSettingsPlugins;
        this.dependencySubstitutions = dependencySubstitutions;
        this.fromBuild = fromBuild;
        this.pluginBuild = pluginBuild;
    }

    /**
     * Returns a name to use for this build. Use {@code null} to have a name assigned.
     */
    @Nullable
    public String getName() {
        return name;
    }

    /**
     * Returns the root directory for this build, when known.
     */
    @Nullable
    public File getBuildRootDir() {
        return buildRootDir;
    }

    /**
     * The identity of the build that caused this build to be included.
     *
     * This is not guaranteed to be the parent build WRT the build path, or Gradle instance.
     *
     * Null if the build is the root build.
     */
    @Nullable
    public PublicBuildPath getFromBuild() {
        return fromBuild;
    }

    public StartParameterInternal getStartParameter() {
        return startParameter;
    }

    public PluginRequests getInjectedPluginRequests() {
        return injectedSettingsPlugins;
    }

    public Action<? super DependencySubstitutions> getDependencySubstitutions() {
        return dependencySubstitutions;
    }

    public boolean isPluginBuild() {
        return pluginBuild;
    }

    public static BuildDefinition fromStartParameterForBuild(
            StartParameterInternal startParameter,
            String name,
            File buildRootDir,
            PluginRequests pluginRequests,
            Action<? super DependencySubstitutions> dependencySubstitutions,
            PublicBuildPath fromBuild,
            boolean pluginBuild
    ) {
        return new BuildDefinition(
                name,
                buildRootDir,
                startParameterForIncludedBuildFrom(startParameter, buildRootDir),
                pluginRequests,
                dependencySubstitutions,
                fromBuild,
                pluginBuild);
    }

    public static BuildDefinition fromStartParameter(StartParameterInternal startParameter, @Nullable PublicBuildPath fromBuild) {
        return fromStartParameter(startParameter, null, fromBuild);
    }

    public static BuildDefinition fromStartParameter(StartParameterInternal startParameter, @Nullable File rootBuildDir, @Nullable PublicBuildPath fromBuild) {
        return new BuildDefinition(null, rootBuildDir, startParameter, PluginRequests.EMPTY, Actions
                .doNothing(), fromBuild, false);
    }

    private static StartParameterInternal startParameterForIncludedBuildFrom(StartParameterInternal startParameter, File buildRootDir) {
        StartParameterInternal includedBuildStartParam = startParameter.newBuild();
        includedBuildStartParam.setCurrentDir(buildRootDir);
        includedBuildStartParam.doNotSearchUpwards();
        includedBuildStartParam.setInitScripts(startParameter.getInitScripts());
        includedBuildStartParam.setExcludedTaskNames(startParameter.getExcludedTaskNames());
        return includedBuildStartParam;
    }

    /**
     * Creates a defensive copy of this build definition, to isolate this instance from mutations made to the {@link StartParameter} during execution of the build.
     */
    public BuildDefinition newInstance() {
        return new BuildDefinition(name, buildRootDir, startParameter.newInstance(), injectedSettingsPlugins, dependencySubstitutions, fromBuild, pluginBuild);
    }
}