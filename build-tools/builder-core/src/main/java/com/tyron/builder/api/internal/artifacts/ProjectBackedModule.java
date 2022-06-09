package com.tyron.builder.api.internal.artifacts;

import com.tyron.builder.api.artifacts.component.ProjectComponentIdentifier;
import com.tyron.builder.api.internal.project.ProjectInternal;

import java.util.Objects;

public class ProjectBackedModule implements Module {

    private final ProjectInternal project;

    public ProjectBackedModule(ProjectInternal project) {
        this.project = project;
    }

    @Override
    public String getGroup() {
        return project.getGroup().toString();
    }

    @Override
    public String getName() {
        return project.getName();
    }

    @Override
    public String getVersion() {
        return project.getVersion().toString();
    }

    @Override
    public String getStatus() {
        return project.getStatus().toString();
    }

    @Override
    public ProjectComponentIdentifier getProjectId() {
        return project.getOwner().getComponentIdentifier();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        ProjectBackedModule that = (ProjectBackedModule) o;

        return Objects.equals(project, that.project);
    }

    @Override
    public int hashCode() {
        return project != null ? project.hashCode() : 0;
    }
}
