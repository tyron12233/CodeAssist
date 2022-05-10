package com.tyron.builder.api.internal.artifacts.transform;

import com.tyron.builder.api.file.FileCollection;
import com.tyron.builder.api.internal.tasks.TaskDependencyContainer;
import com.tyron.builder.internal.Try;

public interface TransformUpstreamDependencies extends TaskDependencyContainer {
    /**
     * Returns a collection containing the future artifacts for the given transformation step.
     */
    FileCollection selectedArtifacts();

    /**
     * Computes the finalized dependency artifacts for the given transformation step.
     */
    Try<ArtifactTransformDependencies> computeArtifacts();

    void finalizeIfNotAlready();
}
