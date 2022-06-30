package com.tyron.builder.launcher.configuration;

import com.tyron.builder.api.internal.StartParameterInternal;
import com.tyron.builder.initialization.BuildLayoutParameters;

import java.io.File;

/**
 * Immutable build layout, calculated from the command-line options and environment.
 */
public interface BuildLayoutResult {
    void applyTo(BuildLayoutParameters buildLayout);

    void applyTo(StartParameterInternal startParameter);

    File getGradleUserHomeDir();
}
