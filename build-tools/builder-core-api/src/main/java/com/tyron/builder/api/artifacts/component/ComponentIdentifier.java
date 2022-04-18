package com.tyron.builder.api.artifacts.component;

/**
 * An opaque immutable identifier for a component instance. There are various sub-interfaces that expose specific details about the identifier.
 *
 * @since 1.10
 */
//@UsedByScanPlugin
public interface ComponentIdentifier {
    /**
     * Returns a human-consumable display name for this identifier.
     *
     * @return Component identifier display name
     * @since 1.10
     */
    String getDisplayName();
}
