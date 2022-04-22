package com.tyron.builder.internal.build;

import com.tyron.builder.api.artifacts.component.BuildIdentifier;
import com.tyron.builder.internal.DisplayName;
import com.tyron.builder.api.internal.GradleInternal;
import com.tyron.builder.util.Path;
import com.tyron.builder.initialization.IncludedBuildSpec;

import java.io.File;
import java.util.function.Function;

/**
 * Encapsulates the identity and state of a particular build in a build tree.
 *
 * Implementations are not yet entirely thread-safe, but should be.
 */
public interface BuildState {
    DisplayName getDisplayName();

    /**
     * Returns the identifier for this build. The identifier is fixed for the lifetime of the build.
     */
    BuildIdentifier getBuildIdentifier();

    /**
     * Returns an identifying path for this build in the build tree. This path is fixed for the lifetime of the build.
     */
    Path getIdentityPath();

    /**
     * Is this an implicit build? An implicit build is one that is managed by Gradle and which is not addressable by user build logic.
     */
    boolean isImplicitBuild();

    /**
     * Should this build be imported into an IDE? Some implicit builds, such as source dependency builds, are not intended to be imported into the IDE or editable by users.
     */
    boolean isImportableBuild();

    /**
     * Note: may change value over the lifetime of this build, as this is often a function of the name of the root project in the build and this is not known until the settings have been configured. A temporary value will be returned when child builds need to create projects for some reason.
     */
    Path getCurrentPrefixForProjectsInChildBuilds();

    /**
     * Calculates the identity path for a project in this build.
     */
    Path calculateIdentityPathForProject(Path projectPath) throws IllegalStateException;

    /**
     * Loads the projects for this build so that {@link #getProjects()} can be used, if not already done.
     * This may include running the settings script for the build, or loading this information from cache.
     */
    void ensureProjectsLoaded();

    /**
     * Ensures all projects in this build are configured, if not already done.
     */
    void ensureProjectsConfigured();

    /**
     * Returns the projects of this build. Fails if the projects are not yet loaded for this build.
     */
    BuildProjectRegistry getProjects();

    /**
     * Asserts that the given build can be included by this build.
     */
    void assertCanAdd(IncludedBuildSpec includedBuildSpec);

    /**
     * The root directory of the build.
     */
    File getBuildRootDir();

    /**
     * Returns the current state of the mutable model of this build.
     */
    GradleInternal getMutableModel();

    /**
     * Returns the work graph for this build.
     */
    BuildWorkGraphController getWorkGraph();

    /**
     * Runs an action against the tooling model creators of this build. May configure the build as required.
     */
    <T> T withToolingModels(Function<? super BuildToolingModelController, T> action);
}