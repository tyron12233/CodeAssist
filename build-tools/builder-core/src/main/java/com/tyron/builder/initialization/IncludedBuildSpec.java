package com.tyron.builder.initialization;

import com.tyron.builder.api.Action;
import com.tyron.builder.api.initialization.ConfigurableIncludedBuild;
import com.tyron.builder.api.initialization.ConfigurableIncludedPluginBuild;
import com.tyron.builder.api.internal.BuildDefinition;
import com.tyron.builder.internal.ImmutableActionSet;
import com.tyron.builder.api.internal.StartParameterInternal;
import com.tyron.builder.internal.reflect.Instantiator;
import com.tyron.builder.internal.build.PublicBuildPath;
import com.tyron.builder.internal.composite.DefaultConfigurableIncludedBuild;
import com.tyron.builder.internal.composite.DefaultConfigurableIncludedPluginBuild;
import com.tyron.builder.plugin.management.internal.PluginRequests;

import java.io.File;

public abstract class IncludedBuildSpec {

    public final File rootDir;

    protected IncludedBuildSpec(File rootDir) {
        this.rootDir = rootDir;
    }

    public abstract BuildDefinition toBuildDefinition(StartParameterInternal startParameter, PublicBuildPath publicBuildPath, Instantiator instantiator);

    public static IncludedBuildSpec includedPluginBuild(File rootDir, Action<? super com.tyron.builder.api.initialization.ConfigurableIncludedPluginBuild> configurer) {
        return new IncludedPluginBuildSpec(rootDir, configurer);
    }

    public static IncludedBuildSpec includedBuild(File rootDir, Action<ConfigurableIncludedBuild> configurer) {
        return new IncludedLibraryBuildSpec(rootDir, configurer);
    }

    private static class IncludedLibraryBuildSpec extends IncludedBuildSpec {

        private final Action<? super ConfigurableIncludedBuild> configurer;

        private IncludedLibraryBuildSpec(File rootDir, Action<? super ConfigurableIncludedBuild> configurer) {
            super(rootDir);
            this.configurer = configurer;
        }

        @Override
        public BuildDefinition toBuildDefinition(StartParameterInternal startParameter, PublicBuildPath publicBuildPath, Instantiator instantiator) {
            DefaultConfigurableIncludedBuild configurable = instantiator.newInstance(DefaultConfigurableIncludedBuild.class, rootDir);
            configurer.execute(configurable);

            return BuildDefinition.fromStartParameterForBuild(
                    startParameter,
                    configurable.getName(),
                    rootDir,
                    PluginRequests.EMPTY,
                    configurable.getDependencySubstitutionAction(),
                    publicBuildPath,
                    false
            );
        }
    }

    private static class IncludedPluginBuildSpec extends IncludedBuildSpec {

        private final Action<? super com.tyron.builder.api.initialization.ConfigurableIncludedPluginBuild> configurer;

        private IncludedPluginBuildSpec(File rootDir, Action<? super ConfigurableIncludedPluginBuild> configurer) {
            super(rootDir);
            this.configurer = configurer;
        }

        @Override
        public BuildDefinition toBuildDefinition(StartParameterInternal startParameter, PublicBuildPath publicBuildPath, Instantiator instantiator) {
            DefaultConfigurableIncludedPluginBuild configurable = instantiator.newInstance(DefaultConfigurableIncludedPluginBuild.class, rootDir);
            configurer.execute(configurable);

            return BuildDefinition.fromStartParameterForBuild(
                    startParameter,
                    configurable.getName(),
                    rootDir,
                    PluginRequests.EMPTY,
                    ImmutableActionSet.empty(),
                    publicBuildPath,
                    true
            );
        }
    }
}