package com.tyron.builder.api.artifacts.result;

import java.io.File;

/**
 * The result of successfully resolving an artifact.
 *
 * @since 2.0
 */
public interface ResolvedArtifactResult extends ArtifactResult {
    /**
     * The file for the artifact.
     */
    File getFile();

    /**
     * The variant that included this artifact.
     */
    ResolvedVariantResult getVariant();
}
