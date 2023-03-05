package org.gradle.tooling.internal.provider.connection;

import org.gradle.api.logging.LogLevel;
import org.gradle.api.logging.configuration.LoggingConfiguration;
import org.gradle.cli.CommandLineConverter;
import org.gradle.cli.CommandLineParser;
import org.gradle.cli.ParsedCommandLine;
import org.gradle.internal.logging.DefaultLoggingConfiguration;
import org.gradle.internal.logging.LoggingConfigurationBuildOptions;

import java.util.Collections;
import java.util.List;

public class BuildLogLevelMixIn {
    private final ProviderOperationParameters parameters;

    public BuildLogLevelMixIn(ProviderOperationParameters parameters) {
        this.parameters = parameters;
    }

    public LogLevel getBuildLogLevel() {
        LoggingConfigurationBuildOptions loggingBuildOptions = new LoggingConfigurationBuildOptions();
        CommandLineConverter<LoggingConfiguration> converter = loggingBuildOptions.commandLineConverter();
        CommandLineParser parser = new CommandLineParser().allowUnknownOptions().allowMixedSubcommandsAndOptions();
        converter.configure(parser);
        List<String> arguments = parameters.getArguments();
        ParsedCommandLine parsedCommandLine = parser.parse(arguments == null ? Collections.<String>emptyList() : arguments);
        //configure verbosely only if arguments do not specify any log level.
        if (parameters.getVerboseLogging() && !parsedCommandLine.hasAnyOption(loggingBuildOptions.getLogLevelOptions())) {
            return LogLevel.DEBUG;
        }

        LoggingConfiguration loggingConfiguration = converter.convert(parsedCommandLine, new DefaultLoggingConfiguration());
        return loggingConfiguration.getLogLevel();
    }
}
