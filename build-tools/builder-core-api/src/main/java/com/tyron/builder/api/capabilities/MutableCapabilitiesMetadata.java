package com.tyron.builder.api.capabilities;

/**
 * Describes the capabilities of a component in a mutable way.
 * This interface can be used to adjust the capabilities of a published component via
 * metadata rules (see {@link com.tyron.builder.api.artifacts.dsl.ComponentMetadataHandler}.
 *
 * @since 4.7
 */
public interface MutableCapabilitiesMetadata extends CapabilitiesMetadata {

    /**
     * Adds a new capability. If a capability of the same (group, name) is found with a different
     * version, an error will be thrown.
     *
     * @param group the group of the capability
     * @param name the name of the capability
     * @param version the version of the capability
     */
    void addCapability(String group, String name, String version);

    /**
     * Removes a capability.
     * @param group the group of the capability
     * @param name the name of the capability
     */
    void removeCapability(String group, String name);

    /**
     * Returns an immutable vew of the capabilities.
     * @return an immutable view of the capabilities
     */
    CapabilitiesMetadata asImmutable();
}
