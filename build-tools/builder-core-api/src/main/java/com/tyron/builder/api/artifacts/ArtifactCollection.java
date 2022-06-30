package com.tyron.builder.api.artifacts;

import com.tyron.builder.api.artifacts.result.ResolvedArtifactResult;
import com.tyron.builder.api.file.FileCollection;

import java.util.Collection;
import java.util.Set;

/**
 * A collection of artifacts resolved for a configuration. The configuration is resolved on demand when
 * the collection is queried.
 *
 * @since 3.4
 */
public interface ArtifactCollection extends Iterable<ResolvedArtifactResult> {
    /**
     * A file collection containing the files for all artifacts in this collection.
     * This is primarily useful to wire this artifact collection as a task input.
     */
    FileCollection getArtifactFiles();

    /**
     * Returns the resolved artifacts, performing the resolution if required.
     * This will resolve the artifact metadata and download the artifact files as required.
     *
     * @throws ResolveException On failure to resolve or download any artifact.
     */
    Set<ResolvedArtifactResult> getArtifacts();

    /**
     * Returns any failures to resolve the artifacts for this collection.
     *
     * @since 4.0
     *
     * @return A collection of exceptions, one for each failure in resolution.
     */
    Collection<Throwable> getFailures();
}
