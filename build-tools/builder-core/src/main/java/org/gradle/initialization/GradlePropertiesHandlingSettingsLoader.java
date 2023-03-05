package org.gradle.initialization;

import org.gradle.api.internal.GradleInternal;
import org.gradle.api.internal.SettingsInternal;
import org.gradle.initialization.layout.BuildLayoutConfiguration;
import org.gradle.initialization.layout.BuildLayoutFactory;

public class GradlePropertiesHandlingSettingsLoader implements SettingsLoader {
    private final SettingsLoader delegate;
    private final BuildLayoutFactory buildLayoutFactory;
    private final GradlePropertiesController gradlePropertiesController;

    public GradlePropertiesHandlingSettingsLoader(SettingsLoader delegate, BuildLayoutFactory buildLayoutFactory, GradlePropertiesController gradlePropertiesController) {
        this.delegate = delegate;
        this.buildLayoutFactory = buildLayoutFactory;
        this.gradlePropertiesController = gradlePropertiesController;
    }

    @Override
    public SettingsInternal findAndLoadSettings(GradleInternal gradle) {
        SettingsLocation settingsLocation = buildLayoutFactory.getLayoutFor(new BuildLayoutConfiguration(gradle.getStartParameter()));
        gradlePropertiesController.loadGradlePropertiesFrom(settingsLocation.getSettingsDir());
        return delegate.findAndLoadSettings(gradle);
    }
}