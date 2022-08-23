package com.tyron.builder.model;

import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.util.Collection;

/**
 * A set of dependencies for an {@link AndroidArtifact}.
 */
public interface Dependencies {

    /**
     * The list of Android library dependencies.
     *
     * <p>On versions &lt; 3.0, the list contains direct dependencies only, which themselves contain
     * their transitive dependencies. Starting with version 3.0, the list is flattened and contains
     * all the transitive dependencies.
     *
     * <p>This includes both modules and external dependencies. They can be differentiated with
     * {@link CodeAssistLibrary#getProject()}.
     *
     * @return the list of libraries.
     */
    @NotNull
    Collection<AndroidLibrary> getLibraries();

    /**
     * The list of Java library dependencies.
     *
     * <p>This is a flattened list containing all transitive external dependencies.
     *
     * @return the list of Java library dependencies.
     */
    @NotNull
    Collection<JavaLibrary> getJavaLibraries();

    /**
     * The list of project dependencies. This is only for non Android module dependencies (which
     * right now is Java-only modules).
     *
     * <p>IMPORTANT: This is not compatible with Composite Builds. This should not be used anymore
     * starting with version 3.1. This is now superseded by {@link #getJavaModules()}.
     *
     * @return the list of projects.
     * @see #getJavaLibraries()
     * @see #getJavaModules()
     */
    @NotNull
    @Deprecated
    Collection<String> getProjects();

    /** ' A Unique identifier for a project. */
    interface ProjectIdentifier {
        /** The build id. This is typically the root dir of the build */
        @NotNull
        String getBuildId();

        /** The project path. This is unique for a given build, but not across builds. */
        @NotNull
        String getProjectPath();
    }

    /** Returns the list of Java Modules. @Since 3.1 */
    @NotNull
    Collection<ProjectIdentifier> getJavaModules();

    /** Returns the list of runtime only dependency classes. @Since 3.5 */
    @NotNull
    Collection<File> getRuntimeOnlyClasses();
}