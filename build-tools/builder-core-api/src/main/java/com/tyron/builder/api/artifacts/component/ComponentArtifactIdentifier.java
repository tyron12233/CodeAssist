package com.tyron.builder.api.artifacts.component;

/**
 * An opaque immutable identifier for an artifact that belongs to some component instance.
 */
public interface ComponentArtifactIdentifier {
    /**
     * Returns the id of the component that this artifact belongs to.
     */
    ComponentIdentifier getComponentIdentifier();

    /**
     * Returns some human-consumable display name for this artifact.
     */
    String getDisplayName();
}
