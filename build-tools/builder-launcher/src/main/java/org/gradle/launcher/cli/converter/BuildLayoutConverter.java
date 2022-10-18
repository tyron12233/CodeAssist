package org.gradle.launcher.cli.converter;

import org.gradle.api.internal.StartParameterInternal;
import org.gradle.cli.CommandLineConverter;
import org.gradle.cli.CommandLineParser;
import org.gradle.cli.ParsedCommandLine;
import org.gradle.initialization.BuildLayoutParameters;
import org.gradle.initialization.BuildLayoutParametersBuildOptions;
import org.gradle.initialization.LayoutCommandLineConverter;
import org.gradle.launcher.configuration.BuildLayoutResult;
import org.gradle.launcher.configuration.InitialProperties;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.Map;
import java.util.function.Consumer;

public class BuildLayoutConverter {
    private final CommandLineConverter<BuildLayoutParameters> buildLayoutConverter = new LayoutCommandLineConverter();

    public void configure(CommandLineParser parser) {
        buildLayoutConverter.configure(parser);
    }

    public BuildLayoutResult defaultValues() {
        return new Result(new BuildLayoutParameters());
    }

    public BuildLayoutResult convert(InitialProperties systemProperties, ParsedCommandLine
    commandLine, @Nullable File workingDir) {
        return convert(systemProperties, commandLine, workingDir, parameters -> {
        });
    }

    public BuildLayoutResult convert(InitialProperties systemProperties, ParsedCommandLine
    commandLine, @Nullable File workingDir, Consumer<BuildLayoutParameters> defaults) {
        BuildLayoutParameters layoutParameters = new BuildLayoutParameters();
        if (workingDir != null) {
            layoutParameters.setCurrentDir(workingDir);
        }
        defaults.accept(layoutParameters);
        Map<String, String> requestedSystemProperties = systemProperties
        .getRequestedSystemProperties();
        new BuildLayoutParametersBuildOptions().propertiesConverter().convert
        (requestedSystemProperties, layoutParameters);
        buildLayoutConverter.convert(commandLine, layoutParameters);
        return new Result(layoutParameters);
    }

    private static class Result implements BuildLayoutResult {
        private final BuildLayoutParameters buildLayout;

        public Result(BuildLayoutParameters buildLayout) {
            this.buildLayout = buildLayout;
        }

        @Override
        public void applyTo(BuildLayoutParameters buildLayout) {
            buildLayout.setCurrentDir(this.buildLayout.getCurrentDir());
            buildLayout.setProjectDir(this.buildLayout.getProjectDir());
            buildLayout.setGradleUserHomeDir(this.buildLayout.getGradleUserHomeDir());
            buildLayout.setGradleInstallationHomeDir(
                    this.buildLayout.getGradleInstallationHomeDir());
        }

        @Override
        public void applyTo(StartParameterInternal startParameter) {
            startParameter.setProjectDir(buildLayout.getProjectDir());
            startParameter.setCurrentDir(buildLayout.getCurrentDir());
            startParameter.setGradleUserHomeDir(buildLayout.getGradleUserHomeDir());
        }

        @Override
        public File getGradleUserHomeDir() {
            return buildLayout.getGradleUserHomeDir();
        }
    }
}
