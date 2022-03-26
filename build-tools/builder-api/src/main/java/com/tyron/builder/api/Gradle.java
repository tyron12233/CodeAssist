package com.tyron.builder.api;

import com.tyron.builder.api.execution.TaskExecutionGraph;
import com.tyron.builder.api.project.BuildProject;

public interface Gradle {

    /**
     * Returns the root project of this build.
     *
     * @return The root project. Never returns null.
     * @throws IllegalStateException When called before the root project is available.
     */
    BuildProject getRootProject() throws IllegalStateException;

    /**
     * Adds an action to execute against the root project of this build.
     *
     * If the root project is already available, the action
     * is executed immediately. Otherwise, the action is executed when the root project becomes available.
     *
     * @param action The action to execute.
     */
    void rootProject(Action<? super BuildProject> action);

    /**
     * Adds an action to execute against all projects of this build.
     *
     * The action is executed immediately against all projects which are
     * already available. It is also executed as subsequent projects are added to this build.
     *
     * @param action The action to execute.
     */
    void allprojects(Action<? super BuildProject> action);

    /**
     * Returns the {@link TaskExecutionGraph} for this build.
     *
     * @return The task graph. Never returns null.
     */
    TaskExecutionGraph getTaskGraph();

    /**
     * Adds an action to be called immediately before a project is evaluated.
     *
     * @param action The action to execute.
     * @since 3.4
     */
    void beforeProject(Action<? super BuildProject> action);


    /**
     * Adds an action to be called immediately after a project is evaluated.
     *
     * @param action The action to execute.
     * @since 3.4
     */
    void afterProject(Action<? super BuildProject> action);

    /**
     * Adds an action to be called when the projects for the build have been created from the settings.
     *
     * None of the projects have been evaluated.
     *
     * @param action The action to execute.
     * @since 3.4
     */
    void projectsLoaded(Action<? super Gradle> action);

    /**
     * Adds an action to be called when all projects for the build have been evaluated.
     *
     * The project objects are fully configured and are ready to use to populate the task graph.
     *
     * @param action The action to execute.
     * @since 3.4
     */
    void projectsEvaluated(Action<? super Gradle> action);

    /**
     * Returns this {@code Gradle} instance.
     *
     * This method is useful in init scripts to explicitly access Gradle
     * properties and methods. For example, using <code>gradle.parent</code> can express your intent better than using
     * <code>parent</code>. This property also allows you to access Gradle properties from a scope where the property
     * may be hidden, such as, for example, from a method or closure.
     *
     * @return this. Never returns null.
     */
    Gradle getGradle();
}
