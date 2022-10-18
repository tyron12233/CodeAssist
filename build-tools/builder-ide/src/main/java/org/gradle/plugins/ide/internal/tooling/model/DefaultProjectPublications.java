package org.gradle.plugins.ide.internal.tooling.model;

import org.gradle.tooling.internal.gradle.DefaultProjectIdentifier;
import org.gradle.tooling.internal.gradle.GradleProjectIdentity;

import java.io.File;
import java.io.Serializable;
import java.util.List;

public class DefaultProjectPublications implements Serializable, GradleProjectIdentity {
    private List<DefaultGradlePublication> publications;
    private DefaultProjectIdentifier projectIdentifier;

    public List<DefaultGradlePublication> getPublications() {
        return publications;
    }

    public DefaultProjectPublications setPublications(List<DefaultGradlePublication> publications) {
        this.publications = publications;
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

    public DefaultProjectPublications setProjectIdentifier(DefaultProjectIdentifier projectIdentifier) {
        this.projectIdentifier = projectIdentifier;
        return this;
    }
}