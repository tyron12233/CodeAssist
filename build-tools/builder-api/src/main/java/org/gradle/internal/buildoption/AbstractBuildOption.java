package org.gradle.internal.buildoption;

import org.gradle.cli.CommandLineOption;
import org.gradle.cli.CommandLineParser;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Provides a basic infrastructure for build option implementations.
 *
 * @since 4.3
 */
public abstract class AbstractBuildOption<T, V extends CommandLineOptionConfiguration> implements BuildOption<T> {

    protected final String gradleProperty;
    protected final List<V> commandLineOptionConfigurations;

    public AbstractBuildOption(String gradleProperty) {
        this(gradleProperty, (V[]) null);
    }

    @SafeVarargs
    public AbstractBuildOption(String gradleProperty, V... commandLineOptionConfiguration) {
        this.gradleProperty = gradleProperty;
        this.commandLineOptionConfigurations = commandLineOptionConfiguration != null ? Arrays.asList(commandLineOptionConfiguration) : Collections.<V>emptyList();

    }

    @Override
    public String getGradleProperty() {
        return gradleProperty;
    }

    protected boolean isTrue(String value) {
        return value != null && value.trim().equalsIgnoreCase("true");
    }

    protected CommandLineOption configureCommandLineOption(CommandLineParser parser, String[] options, String description, boolean deprecated, boolean incubating) {
        CommandLineOption option = parser.option(options)
                .hasDescription(description);

        if(deprecated) {
            option.deprecated();
        }

        if (incubating) {
            option.incubating();
        }

        return option;
    }
}