package org.gradle.api.artifacts;

import org.gradle.api.Action;
import org.gradle.api.capabilities.Capability;
import org.gradle.internal.HasInternalProtocol;

/**
 * Allows configuring the capabilities resolution strategy.
 * When there's a capability conflict, this object will let you select
 * what to do in that situation. The configuration can either be global,
 * for <i>any</i> capability by calling the {@link #all(Action)} method,
 * or it can be specific to a capability by calling one of the {@link #withCapability(Object, Action)},
 * {@link #withCapability(Capability, Action)} or {@link #withCapability(String, String, Action)} methods.
 *
 * @since 5.6
 */
@HasInternalProtocol
public interface CapabilitiesResolution {
    /**
     * Configures the resolution strategy of capability conflicts for all capabilities.
     *
     * @param action the configuration action
     */
    void all(Action<? super CapabilityResolutionDetails> action);

    /**
     * Configures the resolution strategy of a specific capability. The capability version is <i>irrelevant</i>.
     *
     * @param capability a capability to configure
     *
     * @param action the configuration action
     */
    void withCapability(Capability capability, Action<? super CapabilityResolutionDetails> action);

    /**
     * Configures the resolution strategy of a specific capability.
     *
     * @param group the group of the capability to configure
     * @param name the name of the capability to configure
     *
     * @param action the configuration action
     */
    void withCapability(String group, String name, Action<? super CapabilityResolutionDetails> action);

    /**
     * Configures the resolution strategy of a specific capability.
     *
     * @param notation the notation of the capability to configure
     *
     * @param action the configuration action
     */
    void withCapability(Object notation, Action<? super CapabilityResolutionDetails> action);
}
