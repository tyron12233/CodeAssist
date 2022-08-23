package com.tyron.builder.model;

import org.jetbrains.annotations.NotNull;

import java.io.File;

/**
 * The Actual output for a {@link AndroidArtifact}, which can be one file at the minimum or several
 * APKs in case of pure splits configuration.
 */
public interface AndroidArtifactOutput extends OutputFile {

    /**
     * Returns the name of the task used to generate this artifact output.
     *
     * @return the name of the task.
     */
    @NotNull
    @Deprecated
    String getAssembleTaskName();

    /**
     * The generated manifest for this variant's artifact's output.
     */
    @NotNull
    File getGeneratedManifest();
}