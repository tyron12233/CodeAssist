package com.tyron.builder.internal.build;

import com.tyron.builder.api.internal.project.ProjectStateUnk;
import com.tyron.builder.util.Path;

import javax.annotation.Nullable;
import java.util.Set;

public interface BuildProjectRegistry {
    /**
     * Returns the root project of this build.
     */
    ProjectStateUnk getRootProject();

    /**
     * Returns all projects in this build, in public iteration order.
     */
    Set<? extends ProjectStateUnk> getAllProjects();

    /**
     * Locates a project of this build, failing if the project is not present.
     *
     * @param projectPath The path relative to the root project of this build.
     */
    ProjectStateUnk getProject(Path projectPath);

    /**
     * Locates a project of this build, returning null if the project is not present.
     *
     * @param projectPath The path relative to the root project of this build.
     */
    @Nullable
    ProjectStateUnk findProject(Path projectPath);
}
