package com.tyron.builder.initialization;

import com.tyron.builder.api.internal.properties.GradleProperties;

import java.io.File;

public interface IGradlePropertiesLoader {

    String SYSTEM_PROJECT_PROPERTIES_PREFIX = "org.gradle.project.";

    String ENV_PROJECT_PROPERTIES_PREFIX = "ORG_GRADLE_PROJECT_";

    /**
     * Loads the immutable set of Gradle properties.
     *
     * @since 6.2
     */
    GradleProperties loadGradleProperties(File rootDir);
}