package org.gradle.plugins.ide.internal.tooling.model;

import org.gradle.TaskExecutionRequest;
import org.gradle.tooling.internal.gradle.DefaultProjectIdentifier;
import org.gradle.tooling.internal.protocol.InternalLaunchable;

import javax.annotation.Nullable;
import java.io.File;
import java.io.Serializable;
import java.util.Collections;
import java.util.List;

/**
 * Data used for {@link org.gradle.tooling.model.TaskSelector}.
 */
public class LaunchableGradleTaskSelector implements InternalLaunchable, TaskExecutionRequest, Serializable {
    private String name;
    private String path;
    private String displayName;
    private String description;
    private String taskName;
    private boolean isPublic;
    private DefaultProjectIdentifier projectIdentifier;

    public String getName() {
        return name;
    }

    public LaunchableGradleTaskSelector setName(String name) {
        this.name = name;
        return this;
    }

    @Nullable
    public String getDescription() {
        return description;
    }

    public LaunchableGradleTaskSelector setDescription(String description) {
        this.description = description;
        return this;
    }

    public String getDisplayName() {
        return displayName;
    }

    public LaunchableGradleTaskSelector setDisplayName(String displayName) {
        this.displayName = displayName;
        return this;
    }

    @Override
    public List<String> getArgs() {
        return Collections.singletonList(taskName);
    }

    public LaunchableGradleTaskSelector setTaskName(String taskName) {
        this.taskName = taskName;
        return this;
    }

    @Override
    public String getProjectPath() {
        return projectIdentifier.getProjectPath();
    }

    public boolean isPublic() {
        return isPublic;
    }

    public LaunchableGradleTaskSelector setPublic(boolean isPublic) {
        this.isPublic = isPublic;
        return this;
    }

    public DefaultProjectIdentifier getProjectIdentifier() {
        return projectIdentifier;
    }

    @Override
    public File getRootDir() {
        return projectIdentifier.getBuildIdentifier().getRootDir();
    }

    public LaunchableGradleTaskSelector setProjectIdentifier(DefaultProjectIdentifier projectIdentifier) {
        this.projectIdentifier = projectIdentifier;
        return this;
    }

    public String getPath() {
        return path;
    }

    public LaunchableGradleTaskSelector setPath(String path) {
        this.path = path;
        return this;
    }

    @Override
    public String toString() {
        return "LaunchableGradleTaskSelector{"
                + "name='" + name + "' "
                + "description='" + description + "'}";
    }
}