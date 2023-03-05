package org.gradle.internal.buildoption;

import javax.annotation.Nullable;

/**
 * Configuration for a boolean command line option.
 *
 * @since 4.4
 */
public class BooleanCommandLineOptionConfiguration extends CommandLineOptionConfiguration {

    private final String disabledDescription;

    BooleanCommandLineOptionConfiguration(String longOption, String enabledDescription, String disabledDescription) {
        this(longOption, null, enabledDescription, disabledDescription);
    }

    BooleanCommandLineOptionConfiguration(String longOption, @Nullable String shortOption, String enabledDescription, String disabledDescription) {
        super(longOption, shortOption, enabledDescription);
        assert disabledDescription != null : "disabled description cannot be null";
        this.disabledDescription = disabledDescription;
    }

    public static BooleanCommandLineOptionConfiguration create(String longOption, String enabledDescription, String disabledDescription) {
        return new BooleanCommandLineOptionConfiguration(longOption, enabledDescription, disabledDescription);
    }

    public static BooleanCommandLineOptionConfiguration create(String longOption, String shortOption, String enabledDescription, String disabledDescription) {
        return new BooleanCommandLineOptionConfiguration(longOption, shortOption, enabledDescription, disabledDescription);
    }

    public String getDisabledDescription() {
        return disabledDescription;
    }

    @Override
    public BooleanCommandLineOptionConfiguration incubating() {
        super.incubating();
        return this;
    }

    @Override
    public BooleanCommandLineOptionConfiguration deprecated() {
        super.deprecated();
        return this;
    }
}