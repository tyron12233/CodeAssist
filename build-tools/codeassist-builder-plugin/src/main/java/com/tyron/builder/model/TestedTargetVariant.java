package com.tyron.builder.model;

import org.jetbrains.annotations.NotNull;

/**
 * Class representing the tested variants.
 *
 * This is currently used by the test modules, and contains the same pieces of information
 * as the ones used to define the tested application (and it's variant).
 */
public interface TestedTargetVariant {
    /**
     * Returns the Gradle path of the project that is being tested.
     */
    @NotNull
    String getTargetProjectPath();

    /**
     * Returns the variant of the tested project.
     */
    @NotNull
    String getTargetVariant();
}