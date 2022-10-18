package com.tyron.builder.model;

import org.jetbrains.annotations.NotNull;

/**
 * An association of an {@link ArtifactMetaData}'s name and a {@link SourceProvider}.
 */
public interface SourceProviderContainer {

    /**
     * Returns the name matching {@link ArtifactMetaData#getName()}
     */
    @NotNull
    String getArtifactName();

    /**
     * Returns the source provider
     */
    @NotNull
    SourceProvider getSourceProvider();
}