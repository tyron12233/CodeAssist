package com.tyron.builder.api.execution;

import com.tyron.builder.api.internal.project.ProjectInternal;

public interface ProjectConfigurer {
    /**
     * Configures the given project.
     */
    void configure(ProjectInternal project);

    /*
     * Configures the project, discovers tasks and binds model rules.
     */
    void configureFully(ProjectInternal project);

    /**
     * Configures the given project and all its sub-projects.
     */
    void configureHierarchy(ProjectInternal project);

    /*
     * Configures the project and all of its sub-projects, including task discovery and binding model rules.
     */
    void configureHierarchyFully(ProjectInternal project);
}