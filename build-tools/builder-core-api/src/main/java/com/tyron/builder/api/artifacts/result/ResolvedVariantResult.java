package com.tyron.builder.api.artifacts.result;

import com.tyron.builder.api.Incubating;
import com.tyron.builder.api.artifacts.component.ComponentIdentifier;
import com.tyron.builder.api.attributes.AttributeContainer;
import com.tyron.builder.api.capabilities.Capability;

import java.util.List;
import java.util.Optional;

/**
 * The result of successfully resolving a component variant.
 *
 * @since 3.5
 */
public interface ResolvedVariantResult {
    /**
     * The component which owns this variant.
     * @return the component identifier of this variant
     *
     * @since 6.8
     */
    @Incubating
    ComponentIdentifier getOwner();

    /**
     * The attributes associated with this variant.
     */
    AttributeContainer getAttributes();

    /**
     * The display name of this variant, for diagnostics.
     *
     * @since 4.6
     */
    String getDisplayName();

    /**
     * The capabilities provided by this variant
     *
     * @since 5.3
     */
    List<Capability> getCapabilities();

    /**
     * If present, this means that this variant is a bridge to another variant
     * found in another module. This corresponds to variants which are marked
     * as "available-at" in Gradle Module Metadata.
     *
     * @return an optional variant, which if present means it's available externally
     *
     * @since 6.8
     */
    @Incubating
    Optional<ResolvedVariantResult> getExternalVariant();
}
