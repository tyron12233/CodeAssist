package org.gradle.internal.build;

import org.gradle.api.internal.project.ProjectState;
import org.gradle.internal.Factory;
import org.gradle.util.Path;

import javax.annotation.Nullable;
import java.util.Set;
import java.util.function.Consumer;

public interface BuildProjectRegistry {
    /**
     * Returns the root project of this build.
     */
    ProjectState getRootProject();

    /**
     * Returns all projects in this build, in public iteration order.
     */
    Set<? extends ProjectState> getAllProjects();

    /**
     * Locates a project of this build, failing if the project is not present.
     *
     * @param projectPath The path relative to the root project of this build.
     */
    ProjectState getProject(Path projectPath);

    /**
     * Locates a project of this build, returning null if the project is not present.
     *
     * @param projectPath The path relative to the root project of this build.
     */
    @Nullable
    ProjectState findProject(Path projectPath);

    /**
     * Allows a section of code to run against the mutable state of all projects of this build. No other thread will be able to access the state of any project of this build while the given action is running.
     *
     * <p>Any attempt to lock a project by some other thread will block while the given action is running. This includes calls to {@link ProjectState#applyToMutableState(Consumer)}.
     */
    void withMutableStateOfAllProjects(Runnable runnable);

    /**
     * Allows a section of code to run against the mutable state of all projects of this build. No other thread will be able to access the state of any project of this build while the given action is running.
     *
     * <p>Any attempt to lock a project by some other thread will block while the given action is running. This includes calls to {@link ProjectState#applyToMutableState(Consumer)}.
     */
    <T> T withMutableStateOfAllProjects(Factory<T> factory);
}