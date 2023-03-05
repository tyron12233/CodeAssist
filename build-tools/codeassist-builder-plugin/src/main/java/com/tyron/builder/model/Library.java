package com.tyron.builder.model;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public interface Library {

    /**
     * Returns an optional build identifier.
     *
     * <p>This is only valid if {@link #getProject()} is not <code>null</code>. a <code>null</code>
     * value in this property indicates that this is the root build.
     *
     * @return a build identifier or null.
     */
    @Nullable
    String getBuildId();

    /**
     * Returns an optional project identifier if the library is output
     * by a module.
     *
     * @return the project identifier
     */
    @Nullable
    String getProject();

    /**
     * Returns a user friendly name.
     */
    @Nullable
    String getName();

    /**
     * Returns this library's Maven coordinates, as requested in the build file.
     */
    @Nullable
    MavenCoordinates getRequestedCoordinates();

    /**
     * Returns this library's Maven coordinates after all the project's dependencies have been
     * resolved. This coordinate may be different than {@link #getRequestedCoordinates()}.
     */
    @NotNull
    MavenCoordinates getResolvedCoordinates();

    /**
     * Returns whether the dependency is skipped.
     *
     * This can happen in testing artifacts when the same dependency is present in
     * both the tested artifact and the test artifact.
     * @return true if skipped.
     */
    boolean isSkipped();

    /**
     * Returns whether the dependency is provided.
     *
     * This is only valid for dependencies present in the 'compile' graph.
     *
     * In the 'package' graph the value is always <code>false</code> since the provided dependencies
     * are not present
     *
     * @return true if provided.
     */
    boolean isProvided();
}