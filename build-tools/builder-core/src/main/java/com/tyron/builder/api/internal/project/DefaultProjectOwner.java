package com.tyron.builder.api.internal.project;

import com.tyron.builder.api.artifacts.component.ProjectComponentIdentifier;
import com.tyron.builder.api.internal.initialization.ClassLoaderScope;
import com.tyron.builder.internal.DisplayName;
import com.tyron.builder.api.internal.artifacts.DefaultBuildIdentifier;
import com.tyron.builder.api.internal.artifacts.DefaultProjectComponentIdentifier;
import com.tyron.builder.internal.build.BuildState;
import com.tyron.builder.internal.resources.ResourceLock;
import com.tyron.builder.util.Path;
import com.tyron.builder.internal.model.CalculatedModelValue;

import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

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
    private Path identityPath;
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
        return identityPath;
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

    @Override
    public ProjectComponentIdentifier getComponentIdentifier() {
        return new DefaultProjectComponentIdentifier(
                new DefaultBuildIdentifier(getName()),
                getIdentityPath(),
                getProjectPath(),
                getName()
        );
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
    public void createMutableModel(ClassLoaderScope selfClassLoaderScope,
                                   ClassLoaderScope baseClassLoaderScope) {

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

    @Override
    public <S> S fromMutableState(Function<? super ProjectInternal, ? extends S> factory) {
        return null;
    }

    @Override
    public <S> S forceAccessToMutableState(Function<? super ProjectInternal, ? extends S> factory) {
        return null;
    }

    @Override
    public void applyToMutableState(Consumer<? super ProjectInternal> action) {

    }

    @Override
    public boolean hasMutableState() {
        return false;
    }

    @Override
    public <S> CalculatedModelValue<S> newCalculatedValue(@Nullable S initialValue) {
        return null;
    }

    public static class Builder {

        private DisplayName displayName;
        private File projectDir;
        private Path projectPath;
        private Path identityPath;
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

        public Builder setIdentityPath(Path path) {
            this.identityPath = path;
            return this;
        }

        public DefaultProjectOwner build() {
            DefaultProjectOwner owner = new DefaultProjectOwner();
            owner.setProjectDir(projectDir);
            owner.setProjectPath(projectPath);
            owner.setDisplayName(displayName);
            owner.setAccessLock(accessLock);
            owner.setTaskExecutionLock(taskExecutionLock);
            owner.identityPath = identityPath;
            return owner;
        }
    }
}
