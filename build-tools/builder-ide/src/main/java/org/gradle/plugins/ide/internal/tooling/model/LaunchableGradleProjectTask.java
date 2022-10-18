package org.gradle.plugins.ide.internal.tooling.model;

public class LaunchableGradleProjectTask extends LaunchableGradleTask {
    private DefaultGradleProject project;

    public DefaultGradleProject getProject() {
        return project;
    }

    public LaunchableGradleProjectTask setProject(DefaultGradleProject project) {
        this.project = project;
        return this;
    }
}