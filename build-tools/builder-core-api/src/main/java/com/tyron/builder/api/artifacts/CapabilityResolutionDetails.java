package com.tyron.builder.api.artifacts;

import com.tyron.builder.api.capabilities.Capability;
import com.tyron.builder.internal.HasInternalProtocol;

import java.util.List;

/**
 * Gives access to the resolution details of a single capability conflict.
 * This class may be used to resolve a capability conflict by either selecting
 * explicitly one of the candidates, or selecting the one with the highest
 * version of the capability.
 *
 * @since 5.6
 */
@HasInternalProtocol
public interface CapabilityResolutionDetails {
    /**
     * Returns the capability in conflict
     */
    Capability getCapability();

    /**
     * Returns the list of components which are in conflict on this capability
     */
    List<ComponentVariantIdentifier> getCandidates();

    /**
     * Selects a particular candidate to solve the conflict. It is recommended to
     * provide a human-readable explanation to the choice by calling the {@link #because(String)} method
     *
     * @param candidate the selected candidate
     * @return this details instance
     *
     * @since 6.0
     */
    CapabilityResolutionDetails select(ComponentVariantIdentifier candidate);

    /**
     * Selects a particular candidate to solve the conflict. It is recommended to
     * provide a human-readable explanation to the choice by calling the {@link #because(String)} method
     *
     * @param notation the selected candidate
     *
     * @return this details instance
     */
    CapabilityResolutionDetails select(Object notation);

    /**
     * Automatically selects the candidate module which has the highest version of the
     * capability. A reason is automatically added so calling {@link #because(String)} would override
     * the automatic selection description.
     *
     * @return this details instance
     */
    CapabilityResolutionDetails selectHighestVersion();

    /**
     * Describes why a particular candidate is selected.
     *
     * @param reason the reason why a candidate is selected.
     *
     * @return this details instance
     */
    CapabilityResolutionDetails because(String reason);
}
