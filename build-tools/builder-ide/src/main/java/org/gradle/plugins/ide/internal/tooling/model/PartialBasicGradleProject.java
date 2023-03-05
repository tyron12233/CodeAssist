package org.gradle.plugins.ide.internal.tooling.model;

import org.gradle.tooling.internal.gradle.DefaultProjectIdentifier;
import org.gradle.tooling.internal.gradle.GradleProjectIdentity;

import java.io.File;
import java.io.Serializable;
import java.util.LinkedHashSet;
import java.util.Set;

public class PartialBasicGradleProject implements Serializable, GradleProjectIdentity {
    private String name;
    private DefaultProjectIdentifier projectIdentifier;
    private PartialBasicGradleProject parent;
    private Set<PartialBasicGradleProject> children = new LinkedHashSet<PartialBasicGradleProject>();

    @Override
    public String toString() {
        return "GradleProject{path='" + getPath() + "\'}";
    }

    public String getPath() {
        return projectIdentifier.getProjectPath();
    }

    public PartialBasicGradleProject getParent() {
        return parent;
    }

    public PartialBasicGradleProject setParent(PartialBasicGradleProject parent) {
        this.parent = parent;
        return this;
    }

    public String getName() {
        return name;
    }

    public PartialBasicGradleProject setName(String name) {
        this.name = name;
        return this;
    }

    public Set<? extends PartialBasicGradleProject> getChildren() {
        return children;
    }

    public PartialBasicGradleProject addChild(PartialBasicGradleProject child) {
        children.add(child);
        return this;
    }

    public DefaultProjectIdentifier getProjectIdentifier() {
        return projectIdentifier;
    }

    @Override
    public String getProjectPath() {
        return projectIdentifier.getProjectPath();
    }

    @Override
    public File getRootDir() {
        return projectIdentifier.getBuildIdentifier().getRootDir();
    }

    public PartialBasicGradleProject setProjectIdentifier(DefaultProjectIdentifier projectIdentifier) {
        this.projectIdentifier = projectIdentifier;
        return this;
    }
}