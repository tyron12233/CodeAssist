package com.tyron.builder.internal.composite;

import com.tyron.builder.api.internal.GradleInternal;
import com.tyron.builder.api.internal.SettingsInternal;
import com.tyron.builder.initialization.SettingsLoader;

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