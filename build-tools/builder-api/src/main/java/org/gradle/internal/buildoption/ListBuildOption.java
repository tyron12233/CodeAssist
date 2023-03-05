package org.gradle.internal.buildoption;

import org.gradle.cli.CommandLineParser;
import org.gradle.cli.ParsedCommandLine;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * A build option that takes a list value e.g. {@code "-Iinit1.gradle -Iinit2.gradle"}.
 *
 * @since 4.3
 */
public abstract class ListBuildOption<T> extends AbstractBuildOption<T, CommandLineOptionConfiguration> {

    public ListBuildOption(String gradleProperty) {
        super(gradleProperty);
    }

    public ListBuildOption(String gradleProperty, CommandLineOptionConfiguration... commandLineOptionConfigurations) {
        super(gradleProperty, commandLineOptionConfigurations);
    }

    @Override
    public void applyFromProperty(Map<String, String> properties, T settings) {
        String value = properties.get(gradleProperty);

        if (value != null) {
            String[] splitValues = value.split("\\s*,\\s*");
            applyTo(Arrays.asList(splitValues), settings, Origin.forGradleProperty(gradleProperty));
        }
    }

    @Override
    public void configure(CommandLineParser parser) {
        for (CommandLineOptionConfiguration config : commandLineOptionConfigurations) {
            configureCommandLineOption(parser, config.getAllOptions(), config.getDescription(), config.isDeprecated(), config.isIncubating()).hasArguments();
        }
    }

    @Override
    public void applyFromCommandLine(ParsedCommandLine options, T settings) {
        for (CommandLineOptionConfiguration config : commandLineOptionConfigurations) {
            if (options.hasOption(config.getLongOption())) {
                List<String> value = options.option(config.getLongOption()).getValues();
                applyTo(value, settings, Origin.forCommandLine(config.getLongOption()));
            }
        }
    }

    public abstract void applyTo(List<String> values, T settings, Origin origin);
}