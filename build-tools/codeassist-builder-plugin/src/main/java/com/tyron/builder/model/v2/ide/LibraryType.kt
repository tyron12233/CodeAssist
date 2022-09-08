package com.tyron.builder.model.v2.ide

/**
 * The type of Library dependency.
 */
enum class LibraryType {

    /**
     * The dependency is a sub-project of the build.
     */
    PROJECT,

    /**
     * The dependency is an external Android Library (AAR)
     */
    ANDROID_LIBRARY,

    /**
     * The dependency is an external Java Library (JAR)
     */
    JAVA_LIBRARY,

    /**
     * The dependency is an external dependency with no artifact, pointing to a different artifact
     * (via Gradle's available-at feature, and possibly via POM's relocation feature.)
     */
    RELOCATED,

    /**
     * The dependency is an external dependency with no artifact, but it may depend on other
     * libraries.
     */
    NO_ARTIFACT_FILE;
}
