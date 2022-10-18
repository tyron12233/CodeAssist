package org.gradle.internal.composite;

import org.gradle.api.internal.GradleInternal;
import org.gradle.api.internal.SettingsInternal;
import org.gradle.initialization.SettingsLoader;
import org.gradle.internal.build.BuildStateRegistry;

public class CompositeBuildSettingsLoader implements SettingsLoader {
    private final SettingsLoader delegate;
    private final BuildStateRegistry buildRegistry;

    public CompositeBuildSettingsLoader(SettingsLoader delegate, BuildStateRegistry buildRegistry) {
        this.delegate = delegate;
        this.buildRegistry = buildRegistry;
    }

    @Override
    public SettingsInternal findAndLoadSettings(GradleInternal gradle) {
        SettingsInternal settings = delegate.findAndLoadSettings(gradle);

        // Lock-in explicitly included builds
        buildRegistry.finalizeIncludedBuilds();

        return settings;
    }
}