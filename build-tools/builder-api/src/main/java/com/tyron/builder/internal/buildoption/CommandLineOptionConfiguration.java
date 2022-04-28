package com.tyron.builder.internal.buildoption;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Configuration for a command line option.
 *
 * @since 4.3
 */
public class CommandLineOptionConfiguration {
    private final String longOption;
    private final String shortOption;
    private final String description;
    private boolean incubating;
    private boolean deprecated;

    CommandLineOptionConfiguration(String longOption, String description) {
        this(longOption, null, description);
    }

    CommandLineOptionConfiguration(String longOption, @Nullable String shortOption, String description) {
        assert longOption != null : "longOption cannot be null";
        assert description != null : "description cannot be null";
        this.longOption = longOption;
        this.shortOption = shortOption;
        this.description = description;
    }

    public static CommandLineOptionConfiguration create(String longOption, String description) {
        return new CommandLineOptionConfiguration(longOption, description);
    }

    public static CommandLineOptionConfiguration create(String longOption, String shortOption, String description) {
        return new CommandLineOptionConfiguration(longOption, shortOption, description);
    }

    public CommandLineOptionConfiguration incubating() {
        incubating = true;
        return this;
    }

    public CommandLineOptionConfiguration deprecated() {
        deprecated = true;
        return this;
    }

    public String getLongOption() {
        return longOption;
    }

    @Nullable
    public String getShortOption() {
        return shortOption;
    }

    public String[] getAllOptions() {
        List<String> allOptions = new ArrayList<String>();
        allOptions.add(longOption);

        if (shortOption != null) {
            allOptions.add(shortOption);
        }

        return allOptions.toArray(new String[allOptions.size()]);
    }

    public String getDescription() {
        return description;
    }

    public boolean isIncubating() {
        return incubating;
    }

    public boolean isDeprecated() {
        return deprecated;
    }
}