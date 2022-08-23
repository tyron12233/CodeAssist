package com.tyron.builder.model;

import org.jetbrains.annotations.Nullable;

import java.io.File;

/**
 * The information for a generated Java artifact.
 */
public interface JavaArtifact extends BaseArtifact {

    /** Path to the mockable platform jar generated for this {@link JavaArtifact}, if present. */
    @Nullable
    File getMockablePlatformJar();
}