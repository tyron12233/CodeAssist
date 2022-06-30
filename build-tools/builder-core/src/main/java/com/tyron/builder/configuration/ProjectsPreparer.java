package com.tyron.builder.configuration;


import com.tyron.builder.api.internal.GradleInternal;

/**
 * Responsible for creating and configuring the projects of a `Gradle` instance. The result is passed to a {@link org.gradle.initialization.TaskExecutionPreparer} to prepare for task execution. Prior to project preparation, the `Gradle` instance has its settings object configured by a {@link org.gradle.initialization.SettingsPreparer}.
 *
 * <p>This stage includes running the build script for each project.</p>
 */
public interface ProjectsPreparer {
    void prepareProjects(GradleInternal gradle);
}