package com.tyron.builder.api.internal.artifacts.dsl.dependencies;

import com.tyron.builder.api.internal.project.ProjectInternal;

public interface ProjectFinder {
    /**
     * Locates the project with the provided path, failing if not found.
     *
     * @param path Can be relative or absolute
     * @return The project belonging to the path, never null.
     */
    ProjectInternal getProject(String path);
}
