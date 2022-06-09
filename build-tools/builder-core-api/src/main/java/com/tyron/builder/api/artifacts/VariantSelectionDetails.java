package com.tyron.builder.api.artifacts;

import com.tyron.builder.api.Action;
import com.tyron.builder.api.attributes.AttributeContainer;

/**
 * Allows configuring the variant-aware selection aspects of a specific
 * dependency. This includes the ability to substitute a dependency on
 * a platform with another platform, or substitute a dependency without
 * attributes with a dependency with attributes.
 *
 * @since 6.6
 */
public interface VariantSelectionDetails {
    /**
     * Selects the platform variant of a component
     */
    void platform();

    /**
     * Selects the enforced platform variant of a component
     */
    void enforcedPlatform();

    /**
     * Selects the library variant of a component
     */
    void library();

    /**
     * Replaces the provided selector attributes with the attributes configured
     * via the configuration action.
     * @param configurationAction the configuration action
     */
    void attributes(Action<? super AttributeContainer> configurationAction);

    /**
     * Replaces the provided selector capabilities with the capabilities configured
     * via the configuration action.
     * @param configurationAction the configuration action
     */
    void capabilities(Action<? super ModuleDependencyCapabilitiesHandler> configurationAction);

}
