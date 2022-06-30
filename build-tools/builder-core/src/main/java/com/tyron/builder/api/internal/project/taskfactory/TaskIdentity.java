package com.tyron.builder.api.internal.project.taskfactory;

import com.google.common.annotations.VisibleForTesting;
import com.tyron.builder.api.Task;
import com.tyron.builder.api.internal.project.ProjectInternal;
import com.tyron.builder.util.Path;

import java.util.concurrent.atomic.AtomicLong;

public final class TaskIdentity<T extends Task> {

    private static final AtomicLong SEQUENCE = new AtomicLong();

    public final Class<T> type;
    public final String name;
    public final Path projectPath; // path within its build (i.e. including project path)
    public final Path identityPath; // path within the build tree (i.e. including project path)
    public final Path buildPath; // path of the owning build

    /**
     * Tasks can be replaced in Gradle, meaning there can be two different tasks with the same path/type.
     * This allows identifying a precise instance.
     */
    public final long uniqueId;

    @VisibleForTesting
    TaskIdentity(Class<T> type, String name, Path projectPath, Path identityPath, Path buildPath, long uniqueId) {
        this.name = name;
        this.projectPath = projectPath;
        this.identityPath = identityPath;
        this.buildPath = buildPath;
        this.type = type;
        this.uniqueId = uniqueId;
    }

    public static <T extends Task> TaskIdentity<T> create(String name, Class<T> type, ProjectInternal project) {
        return new TaskIdentity<>(
                type,
                name,
                project.getProjectPath().child(name),
                project.getIdentityPath().child(name),
                project.getGradle().getIdentityPath(),
                SEQUENCE.getAndIncrement()
        );
    }

    public long getId() {
        return uniqueId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        TaskIdentity<?> that = (TaskIdentity<?>) o;
        return this.uniqueId == that.uniqueId;
    }

    @Override
    public int hashCode() {
        return (int) (uniqueId ^ uniqueId >>> 32);
    }

    @Override
    public String toString() {
        return "TaskIdentity{path=" + identityPath + ", type=" + type + ", uniqueId=" + uniqueId + '}';
    }

    public String getTaskPath() {
        return projectPath.getPath();
    }

    public String getProjectPath() {
        return projectPath.getParent().getPath();
    }

    public String getIdentityPath() {
        return identityPath.getPath();
    }

    public String getBuildPath() {
        return buildPath.getPath();
    }

    public Class<T> getTaskType() {
        return type;
    }
}