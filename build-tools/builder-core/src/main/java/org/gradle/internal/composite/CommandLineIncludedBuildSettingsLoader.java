package org.gradle.internal.composite;

import org.gradle.api.internal.GradleInternal;
import org.gradle.api.internal.SettingsInternal;
import org.gradle.initialization.SettingsLoader;

import java.io.File;

public class CommandLineIncludedBuildSettingsLoader implements SettingsLoader {
    private final SettingsLoader delegate;

    public CommandLineIncludedBuildSettingsLoader(SettingsLoader delegate) {
        this.delegate = delegate;
    }

    @Override
    public SettingsInternal findAndLoadSettings(GradleInternal gradle) {
        SettingsInternal settings = delegate.findAndLoadSettings(gradle);

        // Add all included builds from the command-line
        for (File rootDir : gradle.getStartParameter().getIncludedBuilds()) {
            settings.includeBuild(rootDir);
        }

        return settings;
    }
}