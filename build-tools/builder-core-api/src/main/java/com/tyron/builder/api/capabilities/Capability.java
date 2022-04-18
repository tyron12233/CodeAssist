package com.tyron.builder.api.capabilities;


import javax.annotation.Nullable;

/**
 * Represents a capability. Capabilities are versioned. Only one component for a specific capability
 * can be found on a dependency graph.
 *
 * @since 4.7
 */
public interface Capability {
    String getGroup();
    String getName();
    @Nullable
    String getVersion();
}
