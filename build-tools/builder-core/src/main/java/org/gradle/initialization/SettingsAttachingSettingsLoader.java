package org.gradle.initialization;

import org.gradle.api.internal.GradleInternal;
import org.gradle.api.internal.SettingsInternal;
import org.gradle.api.internal.project.ProjectStateRegistry;

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