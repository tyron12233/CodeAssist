package com.tyron.builder.initialization;


import com.tyron.builder.api.internal.GradleInternal;

/**
 * Responsible creating and configuring the `Settings` object for a newly created `Gradle` instance. The result is passed to a {@link org.gradle.configuration.ProjectsPreparer} to configure the projects.
 *
 * <p>This stage includes running the init scripts and settings script.</p>
 */
public interface SettingsPreparer {
    void prepareSettings(GradleInternal gradle);
}