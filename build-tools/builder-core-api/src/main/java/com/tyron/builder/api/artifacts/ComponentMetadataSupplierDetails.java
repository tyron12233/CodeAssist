package com.tyron.builder.api.artifacts;

import com.tyron.builder.api.artifacts.component.ModuleComponentIdentifier;

/**
 * A component metadata rule details, giving access to the identifier of the component being
 * resolved, the metadata builder, and the repository resource accessor for this.
 *
 * @since 4.0
 */
public interface ComponentMetadataSupplierDetails {
    /**
     * Returns the identifier of the component being resolved
     * @return the identifier
     */
    ModuleComponentIdentifier getId();

    /**
     * Returns the metadata builder, that users can use to feed metadata for the component.
     * @return the metadata builder
     */
    ComponentMetadataBuilder getResult();

}
