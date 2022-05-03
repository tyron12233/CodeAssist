package com.tyron.builder.api.internal.artifacts.transform;

import com.tyron.builder.api.file.FileCollection;

import java.util.Optional;

public interface ArtifactTransformDependencies {
    /**
     * Returns the dependency artifacts of the artifact being transformed.
     * The order of the files match that of the dependencies in the source artifact view.
     */
    Optional<FileCollection> getFiles();
}
