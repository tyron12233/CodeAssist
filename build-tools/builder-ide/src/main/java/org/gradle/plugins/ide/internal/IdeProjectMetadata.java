package org.gradle.plugins.ide.internal;

import org.gradle.api.Task;
import org.gradle.api.internal.artifacts.ivyservice.projectmodule.ProjectPublication;

import java.io.File;
import java.util.Set;

/**
 * Details of an IDE project shared across Gradle project boundaries.
 */
public interface IdeProjectMetadata extends ProjectPublication {
    File getFile();

    Set<? extends Task> getGeneratorTasks();
}