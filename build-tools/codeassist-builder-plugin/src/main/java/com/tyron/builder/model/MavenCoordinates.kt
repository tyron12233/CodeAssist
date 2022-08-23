package com.tyron.builder.model

/**
 * Coordinates that uniquely identifies a project in a Maven repository.
 */
interface MavenCoordinates {
    /**
     * Returns the name of the project's group, similar to the Java packaging structure.
     */
    val groupId: String

    /**
     * Returns the name that the project is known by.
     */
    val artifactId: String

    /**
     * Returns the version of the project.
     */
    val version: String

    /**
     * Returns the project's artifact type. It defaults to "jar" if not explicitly set.
     */
    val packaging: String

    /**
     * Returns the project's classifier. The classifier allows to distinguish artifacts that were
     * built from the same POM but differ in their content.
     */
    val classifier: String?

    /**
     * Returns this coordinates Id without the version attribute.
     * Since 2.3
     */
    val versionlessId: String
}