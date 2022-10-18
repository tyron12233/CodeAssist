package org.gradle.api.artifacts.result;

import org.gradle.api.artifacts.component.ComponentArtifactIdentifier;
import org.gradle.api.component.Artifact;

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
