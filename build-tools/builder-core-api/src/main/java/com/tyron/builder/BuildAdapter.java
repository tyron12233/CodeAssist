package com.tyron.builder;

import com.tyron.builder.api.initialization.Settings;
import com.tyron.builder.api.invocation.Gradle;

/**
 * A {@link BuildListener} adapter class for receiving build events. The methods in this class are empty. This class
 * exists as convenience for creating listener objects.
 */
public class BuildAdapter implements BuildListener {

    @Override
    public void beforeSettings(Settings settings) {
        BuildListener.super.beforeSettings(settings);
    }

    @Override
    public void settingsEvaluated(Settings settings) {

    }

    @Override
    public void projectsLoaded(Gradle gradle) {
    }

    @Override
    public void projectsEvaluated(Gradle gradle) {
    }

    @Deprecated
    @Override
    public void buildFinished(BuildResult result) {
    }
}