package com.tyron.builder.api.artifacts;

import com.tyron.builder.internal.HasInternalProtocol;

/**
 * The capabilities requested for a dependency. This is used in variant-aware dependency
 * management, to select only variants which provide the requested capabilities. By
 * default, Gradle will only look for variants which provide the "implicit" capability,
 * which corresponds to the GAV coordinates of the component. If the user calls methods
 * on this handler, then the requirements change and explicit capabilities are required.
 *
 * @since 5.3
 */
@HasInternalProtocol
public interface ModuleDependencyCapabilitiesHandler {
    /**
     * Requires a single capability.
     * @param capabilityNotation the capability notation (eg. group:name:version)
     */
    void requireCapability(Object capabilityNotation);

    /**
     * Requires multiple capabilities. The selected variants MUST provide ALL of them
     * to be selected.
     * @param capabilityNotations the capability notations (eg. group:name:version)
     */
    void requireCapabilities(Object... capabilityNotations);
}
