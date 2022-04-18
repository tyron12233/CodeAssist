package com.tyron.builder.initialization;

import com.tyron.builder.api.internal.GradleInternal;
import com.tyron.builder.api.internal.SettingsInternal;
import com.tyron.builder.api.internal.project.ProjectStateRegistry;

class SettingsAttachingSettingsLoader implements SettingsLoader {
    private final SettingsLoader delegate;
    private final ProjectStateRegistry projectRegistry;

    SettingsAttachingSettingsLoader(SettingsLoader delegate, ProjectStateRegistry projectRegistry) {
        this.delegate = delegate;
        this.projectRegistry = projectRegistry;
    }

    @Override
    public SettingsInternal findAndLoadSettings(GradleInternal gradle) {
        SettingsInternal settings = delegate.findAndLoadSettings(gradle);
        gradle.setSettings(settings);
        projectRegistry.registerProjects(gradle.getOwner(), settings.getProjectRegistry());
        return settings;
    }
}