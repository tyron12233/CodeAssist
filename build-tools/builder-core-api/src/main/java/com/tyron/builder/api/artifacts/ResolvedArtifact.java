package com.tyron.builder.api.artifacts;

import com.tyron.builder.api.artifacts.component.ComponentArtifactIdentifier;

import javax.annotation.Nullable;
import java.io.File;

/**
 * Information about a resolved artifact.
 */
public interface ResolvedArtifact {
    /**
     * Returns the local file for this artifact. Downloads the artifact if not already available locally, blocking until complete.
     */
    File getFile();

    /**
     * Returns the module which this artifact belongs to.
     *
     * @return The module.
     */
    ResolvedModuleVersion getModuleVersion();

    String getName();

    String getType();

    String getExtension();

    @Nullable
    String getClassifier();

    ComponentArtifactIdentifier getId();
}
