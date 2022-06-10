package org.gradle.initialization;

import org.gradle.StartParameter;
import org.gradle.api.internal.GradleInternal;
import org.gradle.api.internal.SettingsInternal;
import org.gradle.api.internal.initialization.ClassLoaderScope;

/**
 * Responsible for locating, constructing, and configuring the {@link SettingsInternal} for a build.
 */
public interface SettingsProcessor {
    SettingsInternal process(GradleInternal gradle,
                             SettingsLocation settingsLocation,
                             ClassLoaderScope buildRootClassLoaderScope,
                             StartParameter startParameter);
}

