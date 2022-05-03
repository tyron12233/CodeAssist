package com.tyron.builder.api.artifacts;

import javax.annotation.Nullable;

/**
 * A {@code Dependency} represents a dependency on the artifacts from a particular source. A source can be an Ivy
 * module, a Maven POM, another Gradle project, a collection of Files, etc... A source can have zero or more artifacts.
 */
public interface Dependency {
    String DEFAULT_CONFIGURATION = "default";
    String ARCHIVES_CONFIGURATION = "archives";
    String CLASSIFIER = "m:classifier";

    /**
     * Returns the group of this dependency. The group is often required to find the artifacts of a dependency in a
     * repository. For example, the group name corresponds to a directory name in a Maven like repository. Might return
     * null.
     */
    @Nullable
    String getGroup();

    /**
     * Returns the name of this dependency. The name is almost always required to find the artifacts of a dependency in
     * a repository. Never returns null.
     */
    String getName();

    /**
     * Returns the version of this dependency. The version is often required to find the artifacts of a dependency in a
     * repository. For example the version name corresponds to a directory name in a Maven like repository. Might return
     * null.
     *
     */
    @Nullable
    String getVersion();

    /**
     * Returns whether two dependencies have identical values for their properties. A dependency is an entity with a
     * key. Therefore dependencies might be equal and yet have different properties.
     *
     * @param dependency The dependency to compare this dependency with
     */
    boolean contentEquals(Dependency dependency);

    /**
     * Creates and returns a new dependency with the property values of this one.
     *
     * @return The copy. Never returns null.
     */
    Dependency copy();

    /**
     * Returns a reason why this dependency should be used, in particular with regards to its version. The dependency report
     * will use it to explain why a specific dependency was selected, or why a specific dependency version was used.
     *
     * @return a reason to use this dependency
     *
     * @since 4.6
     */
    @Nullable
    String getReason();

    /**
     * Sets the reason why this dependency should be used.
     *
     * @since 4.6
     */
    void because(@Nullable String reason);
}
