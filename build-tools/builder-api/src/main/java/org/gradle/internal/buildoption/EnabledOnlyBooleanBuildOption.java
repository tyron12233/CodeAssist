package org.gradle.internal.buildoption;

import org.gradle.cli.CommandLineParser;
import org.gradle.cli.ParsedCommandLine;

import java.util.Map;

/**
 * A build option representing a boolean option with a enabled mode only e.g. {@code "--foreground"}.
 *
 * @since 4.3
 */
public abstract class EnabledOnlyBooleanBuildOption<T> extends AbstractBuildOption<T, CommandLineOptionConfiguration> {

    public EnabledOnlyBooleanBuildOption(String gradleProperty) {
        super(gradleProperty, new CommandLineOptionConfiguration[] {});
    }

    public EnabledOnlyBooleanBuildOption(String gradleProperty, CommandLineOptionConfiguration... commandLineOptionConfigurations) {
        super(gradleProperty, commandLineOptionConfigurations);
    }

    @Override
    public void applyFromProperty(Map<String, String> properties, T settings) {
        if (properties.get(gradleProperty) != null) {
            applyTo(settings, Origin.forGradleProperty(gradleProperty));
        }
    }

    @Override
    public void configure(CommandLineParser parser) {
        for (CommandLineOptionConfiguration config : commandLineOptionConfigurations) {
            configureCommandLineOption(parser, config.getAllOptions(), config.getDescription(), config.isDeprecated(), config.isIncubating());
        }
    }

    @Override
    public void applyFromCommandLine(ParsedCommandLine options, T settings) {
        for (CommandLineOptionConfiguration config : commandLineOptionConfigurations) {
            if (options.hasOption(config.getLongOption())) {
                applyTo(settings, Origin.forCommandLine(config.getLongOption()));
            }
        }
    }

    public abstract void applyTo(T settings, Origin origin);
}