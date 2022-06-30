package com.tyron.builder.initialization;


import com.tyron.builder.api.internal.project.ProjectStateRegistry;
import com.tyron.builder.configuration.project.BuiltInCommand;
import com.tyron.builder.initialization.layout.BuildLayoutFactory;
import com.tyron.builder.internal.build.BuildIncluder;
import com.tyron.builder.internal.build.BuildStateRegistry;
import com.tyron.builder.internal.composite.ChildBuildRegisteringSettingsLoader;
import com.tyron.builder.internal.composite.CommandLineIncludedBuildSettingsLoader;
import com.tyron.builder.internal.composite.CompositeBuildSettingsLoader;

import java.util.List;

public class DefaultSettingsLoaderFactory implements SettingsLoaderFactory {
    private final SettingsProcessor settingsProcessor;
    private final BuildStateRegistry buildRegistry;
    private final ProjectStateRegistry projectRegistry;
    private final BuildLayoutFactory buildLayoutFactory;
    private final GradlePropertiesController gradlePropertiesController;
    private final BuildIncluder buildIncluder;
    private final InitScriptHandler initScriptHandler;
    private final List<BuiltInCommand> builtInCommands;

    public DefaultSettingsLoaderFactory(
            SettingsProcessor settingsProcessor,
            BuildStateRegistry buildRegistry,
            ProjectStateRegistry projectRegistry,
            BuildLayoutFactory buildLayoutFactory,
            GradlePropertiesController gradlePropertiesController,
            BuildIncluder buildIncluder,
            InitScriptHandler initScriptHandler,
            List<BuiltInCommand> builtInCommands
    ) {
        this.settingsProcessor = settingsProcessor;
        this.buildRegistry = buildRegistry;
        this.projectRegistry = projectRegistry;
        this.buildLayoutFactory = buildLayoutFactory;
        this.gradlePropertiesController = gradlePropertiesController;
        this.buildIncluder = buildIncluder;
        this.initScriptHandler = initScriptHandler;
        this.builtInCommands = builtInCommands;
    }

    @Override
    public SettingsLoader forTopLevelBuild() {
        return new GradlePropertiesHandlingSettingsLoader(
                new InitScriptHandlingSettingsLoader(
                        new CompositeBuildSettingsLoader(
                                new ChildBuildRegisteringSettingsLoader(
                                        new CommandLineIncludedBuildSettingsLoader(
                                                defaultSettingsLoader()
                                        ),
                                        buildRegistry,
                                        buildIncluder),
                                buildRegistry),
                        initScriptHandler),
                buildLayoutFactory,
                gradlePropertiesController
        );
    }

    @Override
    public SettingsLoader forNestedBuild() {
        return new GradlePropertiesHandlingSettingsLoader(
                new InitScriptHandlingSettingsLoader(
                        new ChildBuildRegisteringSettingsLoader(
                                defaultSettingsLoader(),
                                buildRegistry,
                                buildIncluder),
                        initScriptHandler),
                buildLayoutFactory,
                gradlePropertiesController
        );
    }

    private SettingsLoader defaultSettingsLoader() {
        return new SettingsAttachingSettingsLoader(
                new DefaultSettingsLoader(
                        settingsProcessor,
                        buildLayoutFactory,
                        builtInCommands
                ),
                projectRegistry
        );
    }
}
