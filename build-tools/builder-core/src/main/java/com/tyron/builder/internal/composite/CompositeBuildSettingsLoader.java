package com.tyron.builder.internal.composite;

import com.tyron.builder.api.internal.GradleInternal;
import com.tyron.builder.api.internal.SettingsInternal;
import com.tyron.builder.initialization.SettingsLoader;
import com.tyron.builder.internal.build.BuildStateRegistry;

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