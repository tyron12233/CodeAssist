package com.tyron.builder.api.artifacts.repositories;

/**
 * Extends the repository content descriptor with Maven repositories specific options.
 *
 * @since 5.1
 */
public interface MavenRepositoryContentDescriptor extends RepositoryContentDescriptor {
    /**
     * Declares that this repository only contains releases.
     */
    void releasesOnly();

    /**
     * Declares that this repository only contains snapshots.
     */
    void snapshotsOnly();
}
