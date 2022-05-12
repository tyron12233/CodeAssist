package com.tyron.builder.api.artifacts.repositories;

/**
 * The meta-data provider for an Ivy repository. Uses the Ivy module descriptor ({@code ivy.xml}) to determine the meta-data for module versions and artifacts.
 */
public interface IvyArtifactRepositoryMetaDataProvider {
    /**
     * Returns true if dynamic resolve mode should be used for Ivy modules. When enabled, the {@code revConstraint} attribute for each dependency declaration
     * is used in preference to the {@code rev} attribute. When disabled (the default), the {@code rev} attribute is always used.
     */
    boolean isDynamicMode();

    /**
     * Specifies whether dynamic resolve mode should be used for Ivy modules. When enabled, the {@code revConstraint} attribute for each dependency declaration
     * is used in preference to the {@code rev} attribute. When disabled (the default), the {@code rev} attribute is always used.
     */
    void setDynamicMode(boolean mode);
}
