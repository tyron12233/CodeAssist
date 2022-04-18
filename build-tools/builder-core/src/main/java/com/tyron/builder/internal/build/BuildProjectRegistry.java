package com.tyron.builder.internal.build;

import com.tyron.builder.api.ProjectState;
import com.tyron.builder.internal.Factory;
import com.tyron.builder.api.internal.project.ProjectStateUnk;
import com.tyron.builder.api.util.Path;

import org.jetbrains.annotations.Nullable;

import java.util.Set;
import java.util.function.Consumer;

public interface BuildProjectRegistry {
    /**
     * Returns the root project of this build.
     */
    ProjectStateUnk getRootProject();

    /**
     * Returns all projects in this build, in public iteration order.
     */
    Set<? extends ProjectStateUnk> getAllProjects();

    /**
     * Locates a project of this build, failing if the project is not present.
     *
     * @param projectPath The path relative to the root project of this build.
     */
    ProjectStateUnk getProject(Path projectPath);

    /**
     * Locates a project of this build, returning null if the project is not present.
     *
     * @param projectPath The path relative to the root project of this build.
     */
    @Nullable
    ProjectStateUnk findProject(Path projectPath);

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