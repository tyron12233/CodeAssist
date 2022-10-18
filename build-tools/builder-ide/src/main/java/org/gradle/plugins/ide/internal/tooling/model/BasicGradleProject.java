package org.gradle.plugins.ide.internal.tooling.model;

import org.gradle.tooling.internal.gradle.DefaultProjectIdentifier;

import java.io.File;
import java.util.LinkedHashSet;
import java.util.Set;

public class BasicGradleProject extends PartialBasicGradleProject {
    private File projectDirectory;
    private Set<BasicGradleProject> children = new LinkedHashSet<BasicGradleProject>();


    public File getProjectDirectory() {
        return projectDirectory;
    }

    public BasicGradleProject setProjectDirectory(File projectDirectory) {
        this.projectDirectory = projectDirectory;
        return this;
    }

    @Override
    public BasicGradleProject setProjectIdentifier(DefaultProjectIdentifier projectIdentifier) {
        super.setProjectIdentifier(projectIdentifier);
        return this;
    }

    @Override
    public BasicGradleProject setName(String name) {
        super.setName(name);
        return this;
    }

    @Override
    public Set<? extends BasicGradleProject> getChildren() {
        return children;
    }

    public BasicGradleProject addChild(BasicGradleProject child) {
        children.add(child);
        return this;
    }
}