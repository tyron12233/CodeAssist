package com.tyron.builder.api.artifacts.result;

import com.tyron.builder.api.artifacts.component.ComponentArtifactIdentifier;
import com.tyron.builder.api.component.Artifact;

/**
 * The result of resolving an artifact.
 *
 * @since 2.0
 */
public interface ArtifactResult {
    /**
     * Returns an identifier for this artifact.
     *
     * @since 3.3
     */
    ComponentArtifactIdentifier getId();

    /**
     * Returns the type of this artifact.
     */
    Class<? extends Artifact> getType();
}
