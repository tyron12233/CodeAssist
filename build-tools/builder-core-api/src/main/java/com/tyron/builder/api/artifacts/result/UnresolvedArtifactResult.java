package com.tyron.builder.api.artifacts.result;

/**
 * An artifact that could not be resolved.
 *
 * @since 2.0
 */
public interface UnresolvedArtifactResult extends ArtifactResult {
    /**
     * The failure that occurred when the artifact was resolved.
     */
    Throwable getFailure();
}
