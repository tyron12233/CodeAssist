package com.tyron.builder.api.capabilities;

import java.util.List;

/**
 * Gives access to the list of capabilities of a component.
 *
 * @since 4.7
 */
public interface CapabilitiesMetadata {
    /**
     * Returns an immutable view of the capabilities.
     * @return the list of capabilities. Immutable.
     */
    List<? extends Capability> getCapabilities();
}
