package org.gradle.launcher.cli.converter;

import org.gradle.api.launcher.cli.WelcomeMessageConfiguration;
import org.gradle.api.launcher.cli.WelcomeMessageDisplayMode;
import org.gradle.internal.buildoption.BuildOption;
import org.gradle.internal.buildoption.BuildOptionSet;
import org.gradle.internal.buildoption.EnumBuildOption;
import org.gradle.internal.buildoption.Origin;

import java.util.Collections;
import java.util.List;

public class WelcomeMessageBuildOptions extends BuildOptionSet<WelcomeMessageConfiguration> {

    private static List<BuildOption<WelcomeMessageConfiguration>> options = Collections.singletonList(new WelcomeMessageOption());

    @Override
    public List<? extends BuildOption<? super WelcomeMessageConfiguration>> getAllOptions() {
        return options;
    }

    public static class WelcomeMessageOption extends EnumBuildOption<WelcomeMessageDisplayMode, WelcomeMessageConfiguration> {

        public static final String PROPERTY_NAME = "org.gradle.welcome";

        public WelcomeMessageOption() {
            super(PROPERTY_NAME, WelcomeMessageDisplayMode.class, WelcomeMessageDisplayMode.values(), PROPERTY_NAME);
        }

        @Override
        public void applyTo(WelcomeMessageDisplayMode value, WelcomeMessageConfiguration settings, Origin origin) {
            settings.setWelcomeMessageDisplayMode(value);
        }
    }
}