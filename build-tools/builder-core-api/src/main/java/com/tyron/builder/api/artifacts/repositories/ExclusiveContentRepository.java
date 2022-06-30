package com.tyron.builder.api.artifacts.repositories;

import com.tyron.builder.api.Action;
import com.tyron.builder.internal.Factory;

/**
 * Describes one or more repositories which together constitute the only possible
 * source for an artifact, independently of the others.
 *
 * This means that if a repository declares an include, other repositories will
 * automatically exclude it.
 *
 * @since 6.2
 */
public interface ExclusiveContentRepository {
    /**
     * Declares the repository
     * @param repository the repository for which we declare exclusive content
     * @return this repository descriptor
     */
    ExclusiveContentRepository forRepository(Factory<? extends ArtifactRepository> repository);

    /**
     * Declares the repository
     * @param repositories the repositories for which we declare exclusive content
     * @return this repository descriptor
     */
    ExclusiveContentRepository forRepositories(ArtifactRepository... repositories);

    /**
     * Defines the content filter for this repository
     * @param config the configuration of the filter
     * @return this repository descriptor
     */
    ExclusiveContentRepository filter(Action<? super InclusiveRepositoryContentDescriptor> config);
}
