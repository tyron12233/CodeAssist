package org.gradle.plugins.ide.internal.tooling.model;

import com.google.common.base.MoreObjects;
import org.gradle.tooling.internal.gradle.DefaultProjectIdentifier;
import org.gradle.tooling.internal.gradle.GradleProjectIdentity;
import org.gradle.tooling.model.GradleModuleVersion;

import java.io.File;
import java.io.Serializable;

public class DefaultGradlePublication implements Serializable, GradleProjectIdentity {
    private GradleModuleVersion id;
    private DefaultProjectIdentifier projectIdentifier;

    public GradleModuleVersion getId() {
        return id;
    }

    public DefaultGradlePublication setId(GradleModuleVersion id) {
        this.id = id;
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

    public DefaultGradlePublication setProjectIdentifier(DefaultProjectIdentifier projectIdentifier) {
        this.projectIdentifier = projectIdentifier;
        return this;
    }

    public String toString() {
        return MoreObjects.toStringHelper(this)
                .add("id", id)
                .toString();
    }
}