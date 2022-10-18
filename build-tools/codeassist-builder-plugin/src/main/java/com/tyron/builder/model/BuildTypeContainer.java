package com.tyron.builder.model;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;

/**
 * A Container of all the data related to {@link BuildType}.
 */
public interface BuildTypeContainer {

    /**
     * The Build Type itself.
     *
     * @return the build type
     */
    @NotNull
    BuildType getBuildType();

    /**
     * The associated sources of the build type.
     *
     * @return the build type source provider.
     */
    @Nullable
    SourceProvider getSourceProvider();

    /**
     * Returns a list of ArtifactMetaData/SourceProvider association.
     *
     * @return a list of ArtifactMetaData/SourceProvider association.
     */
    @NotNull
    Collection<SourceProviderContainer> getExtraSourceProviders();
}