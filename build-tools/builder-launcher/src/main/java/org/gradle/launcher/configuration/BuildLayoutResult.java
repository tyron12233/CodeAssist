package org.gradle.launcher.configuration;

import org.gradle.api.internal.StartParameterInternal;
import org.gradle.initialization.BuildLayoutParameters;

import java.io.File;

/**
 * Immutable build layout, calculated from the command-line options and environment.
 */
public interface BuildLayoutResult {
    void applyTo(BuildLayoutParameters buildLayout);

    void applyTo(StartParameterInternal startParameter);

    File getGradleUserHomeDir();
}
