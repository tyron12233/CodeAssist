package com.tyron.builder.initialization;

import com.tyron.builder.api.internal.properties.GradleProperties;
import com.tyron.builder.internal.service.scopes.Scopes;
import com.tyron.builder.internal.service.scopes.ServiceScope;

import java.io.File;

/**
 * Controls the state (not loaded / loaded) of the attached {@link GradleProperties} instance
 * so that the set of Gradle properties is deterministically loaded only once per build.
 */
@ServiceScope(Scopes.Build.class)
public interface GradlePropertiesController {

    /**
     * The {@link GradleProperties} instance attached to this service.
     */
    GradleProperties getGradleProperties();

    /**
     * Loads the immutable set of {@link GradleProperties} from the given directory and
     * makes it available to the build.
     *
     * This method should be called only once per build but multiple calls with the
     * same argument are allowed.
     *
     * @param settingsDir directory where to look for the {@code gradle.properties} file
     * @throws IllegalStateException if called with a different argument in the same build
     */
    void loadGradlePropertiesFrom(File settingsDir);

    /**
     * Unloads the properties so the next call to {@link #loadGradlePropertiesFrom(File)} would reload them and
     * re-evaluate any property defining system properties and environment variables.
     */
    void unloadGradleProperties();
}