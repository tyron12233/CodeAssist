package org.gradle.launcher.cli.converter;

import org.gradle.cli.CommandLineParser;
import org.gradle.cli.ParsedCommandLine;
import org.gradle.internal.buildoption.BuildOptionSet;
import org.gradle.launcher.configuration.AllProperties;

public class BuildOptionBackedConverter<T> {
    private final BuildOptionSet<T> buildOptions;

    public BuildOptionBackedConverter(BuildOptionSet<T> buildOptions) {
        this.buildOptions = buildOptions;
    }

    public void configure(CommandLineParser parser) {
        buildOptions.commandLineConverter().configure(parser);
    }

    public void convert(ParsedCommandLine commandLine, AllProperties properties, T target) {
        buildOptions.propertiesConverter().convert(properties.getProperties(), target);
        buildOptions.commandLineConverter().convert(commandLine, target);
    }
}