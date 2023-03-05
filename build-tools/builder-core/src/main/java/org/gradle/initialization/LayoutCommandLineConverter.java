package org.gradle.initialization;

import org.gradle.cli.AbstractCommandLineConverter;
import org.gradle.cli.CommandLineArgumentException;
import org.gradle.cli.CommandLineConverter;
import org.gradle.cli.CommandLineParser;
import org.gradle.cli.ParsedCommandLine;

public class LayoutCommandLineConverter extends AbstractCommandLineConverter<BuildLayoutParameters> {
    private final CommandLineConverter<BuildLayoutParameters> converter = new BuildLayoutParametersBuildOptions().commandLineConverter();

    @Override
    public BuildLayoutParameters convert(ParsedCommandLine options, BuildLayoutParameters target) throws CommandLineArgumentException {
        converter.convert(options, target);
        return target;
    }

    @Override
    public void configure(CommandLineParser parser) {
        converter.configure(parser);
    }
}