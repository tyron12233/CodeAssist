package com.tyron.builder.initialization;

import com.tyron.builder.StartParameter;
import com.tyron.builder.api.internal.GradleInternal;
import com.tyron.builder.api.internal.SettingsInternal;
import com.tyron.builder.api.internal.initialization.ClassLoaderScope;

/**
 * Responsible for locating, constructing, and configuring the {@link SettingsInternal} for a build.
 */
public interface SettingsProcessor {
    SettingsInternal process(GradleInternal gradle,
                             SettingsLocation settingsLocation,
                             ClassLoaderScope buildRootClassLoaderScope,
                             StartParameter startParameter);
}

