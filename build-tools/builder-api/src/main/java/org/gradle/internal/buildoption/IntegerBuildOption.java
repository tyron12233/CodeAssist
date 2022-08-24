package org.gradle.internal.buildoption;

import org.gradle.cli.CommandLineParser;
import org.gradle.cli.ParsedCommandLine;

import java.util.Map;

public abstract class IntegerBuildOption<T> extends AbstractBuildOption<T, CommandLineOptionConfiguration> {

    public IntegerBuildOption(String gradleProperty) {
        super(gradleProperty);
    }

    public IntegerBuildOption(String gradleProperty, CommandLineOptionConfiguration... commandLineOptionConfigurations) {
        super(gradleProperty, commandLineOptionConfigurations);
    }


    @Override
    public void applyFromProperty(Map<String, String> properties, T settings) {
        String value = properties.get(gradleProperty);
        if (value != null) {
            applyTo(Integer.parseInt(value), settings, Origin.forGradleProperty(gradleProperty));
        }
    }

    @Override
    public void configure(CommandLineParser parser) {
        for (CommandLineOptionConfiguration config : commandLineOptionConfigurations) {
            configureCommandLineOption(parser, config.getAllOptions(), config.getDescription(), config.isDeprecated(), config.isIncubating()).hasArgument();
        }
    }

    @Override
    public void applyFromCommandLine(ParsedCommandLine options, T settings) {
        for (CommandLineOptionConfiguration config : commandLineOptionConfigurations) {
            if (options.hasOption(config.getLongOption())) {
                String value = options.option(config.getLongOption()).getValue();
                applyTo(Integer.parseInt(value), settings, Origin.forCommandLine(config.getLongOption()));
            }
        }
    }

    public abstract void applyTo(int value, T settings, Origin origin);
}