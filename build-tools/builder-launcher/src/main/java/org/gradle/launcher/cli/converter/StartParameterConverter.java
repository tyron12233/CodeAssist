package org.gradle.launcher.cli.converter;

import org.gradle.api.internal.StartParameterInternal;
import org.gradle.api.launcher.cli.WelcomeMessageConfiguration;
import org.gradle.api.logging.configuration.LoggingConfiguration;
import org.gradle.cli.CommandLineArgumentException;
import org.gradle.cli.CommandLineParser;
import org.gradle.cli.ParsedCommandLine;
import org.gradle.cli.ProjectPropertiesCommandLineConverter;
import org.gradle.concurrent.ParallelismConfiguration;
import org.gradle.initialization.ParallelismBuildOptions;
import org.gradle.initialization.StartParameterBuildOptions;
import org.gradle.internal.logging.LoggingConfigurationBuildOptions;
import org.gradle.launcher.configuration.AllProperties;
import org.gradle.launcher.configuration.BuildLayoutResult;

public class StartParameterConverter {
    private final BuildOptionBackedConverter<WelcomeMessageConfiguration> welcomeMessageConfigurationCommandLineConverter = new BuildOptionBackedConverter<>(new WelcomeMessageBuildOptions());
    private final BuildOptionBackedConverter<LoggingConfiguration> loggingConfigurationCommandLineConverter = new BuildOptionBackedConverter<>(new LoggingConfigurationBuildOptions());
    private final BuildOptionBackedConverter<ParallelismConfiguration> parallelConfigurationCommandLineConverter = new BuildOptionBackedConverter<>(new ParallelismBuildOptions());
    private final ProjectPropertiesCommandLineConverter projectPropertiesCommandLineConverter = new ProjectPropertiesCommandLineConverter();
    private final BuildOptionBackedConverter<StartParameterInternal> buildOptionsConverter = new BuildOptionBackedConverter<>(new StartParameterBuildOptions());

    public void configure(CommandLineParser parser) {
        welcomeMessageConfigurationCommandLineConverter.configure(parser);
        loggingConfigurationCommandLineConverter.configure(parser);
        parallelConfigurationCommandLineConverter.configure(parser);
        projectPropertiesCommandLineConverter.configure(parser);
        parser.allowMixedSubcommandsAndOptions();
        buildOptionsConverter.configure(parser);
    }

    public StartParameterInternal convert(ParsedCommandLine parsedCommandLine, BuildLayoutResult buildLayout, AllProperties properties, StartParameterInternal startParameter) throws CommandLineArgumentException {
        buildLayout.applyTo(startParameter);

        welcomeMessageConfigurationCommandLineConverter.convert(parsedCommandLine, properties, startParameter.getWelcomeMessageConfiguration());
        loggingConfigurationCommandLineConverter.convert(parsedCommandLine, properties, startParameter);
        parallelConfigurationCommandLineConverter.convert(parsedCommandLine, properties, startParameter);

        startParameter.getSystemPropertiesArgs().putAll(properties.getRequestedSystemProperties());

        projectPropertiesCommandLineConverter.convert(parsedCommandLine, startParameter.getProjectProperties());

        if (!parsedCommandLine.getExtraArguments().isEmpty()) {
            startParameter.setTaskNames(parsedCommandLine.getExtraArguments());
        }

        buildOptionsConverter.convert(parsedCommandLine, properties, startParameter);

        return startParameter;
    }
}