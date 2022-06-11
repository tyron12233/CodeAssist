package org.gradle.internal.build;

import org.gradle.api.internal.project.ProjectState;
import org.gradle.util.Path;

import javax.annotation.Nullable;
import java.util.Set;

public interface BuildProjectRegistry {
    /**
     * Returns the root project of this build.
     */
    ProjectState getRootProject();

    /**
     * Returns all projects in this build, in public iteration order.
     */
    Set<? extends ProjectState> getAllProjects();

    /**
     * Locates a project of this build, failing if the project is not present.
     *
     * @param projectPath The path relative to the root project of this build.
     */
    ProjectState getProject(Path projectPath);

    /**
     * Locates a project of this build, returning null if the project is not present.
     *
     * @param projectPath The path relative to the root project of this build.
     */
    @Nullable
    ProjectState findProject(Path projectPath);
}
