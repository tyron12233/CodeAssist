package org.gradle.plugins.ide.internal.tooling.model;

import org.gradle.tooling.internal.gradle.DefaultProjectIdentifier;
import org.gradle.tooling.internal.gradle.GradleProjectIdentity;

import java.io.File;
import java.io.Serializable;
import java.util.List;

/**
 * Implementation of {@link org.gradle.tooling.model.gradle.BuildInvocations}
 */
public class DefaultBuildInvocations implements Serializable, GradleProjectIdentity {
    private List<? extends LaunchableGradleTaskSelector> selectors;
    private List<? extends LaunchableGradleTask> tasks;
    private DefaultProjectIdentifier projectIdentifier;

    public DefaultBuildInvocations setSelectors(List<? extends LaunchableGradleTaskSelector> selectors) {
        this.selectors = selectors;
        return this;
    }

    public List<? extends LaunchableGradleTaskSelector> getTaskSelectors() {
        return selectors;
    }

    public DefaultBuildInvocations setTasks(List<? extends LaunchableGradleTask> tasks) {
        this.tasks = tasks;
        return this;
    }

    public List<? extends LaunchableGradleTask> getTasks() {
        return tasks;
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

    public DefaultBuildInvocations setProjectIdentifier(DefaultProjectIdentifier projectIdentifier) {
        this.projectIdentifier = projectIdentifier;
        return this;
    }
}