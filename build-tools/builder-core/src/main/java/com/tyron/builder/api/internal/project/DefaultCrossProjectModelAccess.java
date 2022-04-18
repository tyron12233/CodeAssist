package com.tyron.builder.api.internal.project;

import java.util.Set;
import java.util.TreeSet;

public class DefaultCrossProjectModelAccess implements CrossProjectModelAccess {
    private final ProjectRegistry<ProjectInternal> projectRegistry;

    public DefaultCrossProjectModelAccess(ProjectRegistry<ProjectInternal> projectRegistry) {
        this.projectRegistry = projectRegistry;
    }

    @Override
    public ProjectInternal access(ProjectInternal referrer, ProjectInternal project) {
        return project;
    }

    @Override
    public ProjectInternal findProject(ProjectInternal referrer, ProjectInternal relativeTo, String path) {
        return projectRegistry.getProject(relativeTo.absoluteProjectPath(path));
    }

    @Override
    public Set<? extends ProjectInternal> getSubprojects(ProjectInternal referrer, ProjectInternal relativeTo) {
        return new TreeSet<>(projectRegistry.getSubProjects(relativeTo.getPath()));
    }

    @Override
    public Set<? extends ProjectInternal> getAllprojects(ProjectInternal referrer, ProjectInternal relativeTo) {
        return new TreeSet<>(projectRegistry.getAllProjects(relativeTo.getPath()));
    }
}