package com.tyron.builder.api.artifacts.repositories;

import com.tyron.builder.api.Action;
import com.tyron.builder.internal.HasInternalProtocol;

/**
 * A repository for resolving and publishing artifacts.
 */
@HasInternalProtocol
public interface ArtifactRepository {
    /**
     * Returns the name for this repository. A name must be unique amongst a repository set. A default name is provided for the repository if none
     * is provided.
     *
     * <p>The name is used in logging output and error reporting to point to information related to this repository.
     *
     * @return The name.
     */
    String getName();

    /**
     * Sets the name for this repository.
     *
     * If this repository is to be added to an {@link com.tyron.builder.api.artifacts.ArtifactRepositoryContainer}
     * (including {@link com.tyron.builder.api.artifacts.dsl.RepositoryHandler}), its name cannot be changed after it has
     * been added to the container.
     *
     * @param name The name. Must not be null.
     * @throws IllegalStateException If the name is set after it has been added to the container.
     */
    void setName(String name);

    /**
     * Configures the content of this repository.
     * @param configureAction the configuration action
     *
     * @since 5.1
     */
    void content(Action<? super RepositoryContentDescriptor> configureAction);
}
