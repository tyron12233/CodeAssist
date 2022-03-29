package com.tyron.builder.api.internal.project;

import com.tyron.builder.api.internal.DisplayName;
import com.tyron.builder.api.internal.build.BuildState;
import com.tyron.builder.api.internal.resources.ResourceLock;
import com.tyron.builder.api.util.Path;

import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.Set;

/**
 * For debugging
 */
public class DefaultProjectOwner implements ProjectStateUnk {

    public static Builder builder() {
        return new Builder();
    }

    private DisplayName displayName;
    private File projectDir;
    private Path projectPath;
    private ResourceLock accessLock;
    private ResourceLock taskExecutionLock;

    @Override
    public DisplayName getDisplayName() {
        return displayName;
    }

    public void setDisplayName(DisplayName displayName) {
        this.displayName = displayName;
    }

    @Override
    public BuildState getOwner() {
        return null;
    }

    @Nullable
    @Override
    public ProjectStateUnk getParent() {
        return null;
    }

    @Nullable
    @Override
    public ProjectStateUnk getBuildParent() {
        return null;
    }

    @Override
    public Set<ProjectStateUnk> getChildProjects() {
        return null;
    }

    @Override
    public String getName() {
        return null;
    }

    @Override
    public Path getIdentityPath() {
        return null;
    }

    @Override
    public Path getProjectPath() {
        return projectPath;
    }

    public void setProjectPath(Path projectPath) {
        this.projectPath = projectPath;
    }

    @Override
    public File getProjectDir() {
        return projectDir;
    }

    public void setProjectDir(File projectDir) {
        this.projectDir = projectDir;
    }

    @Override
    public void ensureConfigured() {

    }

    @Override
    public void ensureTasksDiscovered() {

    }

    @Override
    public ProjectInternal getMutableModel() {
        return null;
    }

    @Override
    public ResourceLock getAccessLock() {
        return accessLock;
    }

    public void setAccessLock(ResourceLock accessLock) {
        this.accessLock = accessLock;
    }

    @Override
    public ResourceLock getTaskExecutionLock() {
        return taskExecutionLock;
    }

    public void setTaskExecutionLock(ResourceLock taskExecutionLock) {
        this.taskExecutionLock = taskExecutionLock;
    }

    public static class Builder {

        private DisplayName displayName;
        private File projectDir;
        private Path projectPath;
        private ResourceLock accessLock;
        private ResourceLock taskExecutionLock;

        public Builder setDisplayName(DisplayName displayName) {
            this.displayName = displayName;
            return this;
        }

        public Builder setProjectDir(File projectDir) {
            this.projectDir = projectDir;
            return this;
        }

        public Builder setProjectPath(Path projectPath) {
            this.projectPath = projectPath;
            return this;
        }

        public Builder setAccessLock(ResourceLock accessLock) {
            this.accessLock = accessLock;
            return this;
        }

        public Builder setTaskExecutionLock(ResourceLock taskExecutionLock) {
            this.taskExecutionLock = taskExecutionLock;
            return this;
        }

        public DefaultProjectOwner build() {
            DefaultProjectOwner owner = new DefaultProjectOwner();
            owner.setProjectDir(projectDir);
            owner.setProjectPath(projectPath);
            owner.setDisplayName(displayName);
            owner.setAccessLock(accessLock);
            owner.setTaskExecutionLock(taskExecutionLock);
            return owner;
        }
    }
}
