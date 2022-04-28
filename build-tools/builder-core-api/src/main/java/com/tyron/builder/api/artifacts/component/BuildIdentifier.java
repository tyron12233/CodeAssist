package com.tyron.builder.api.artifacts.component;

/**
 * Identifies a Gradle build. The identifier is unique within a Gradle invocation, so for example, each included build will have a different identifier.
 */
public interface BuildIdentifier {
    /**
     * The name of the build.
     */
    String getName();

    /**
     * Is this build the one that's currently executing?
     */
    boolean isCurrentBuild();
}