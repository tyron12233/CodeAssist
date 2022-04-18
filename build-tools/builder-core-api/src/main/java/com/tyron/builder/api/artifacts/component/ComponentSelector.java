package com.tyron.builder.api.artifacts.component;

import com.tyron.builder.api.attributes.AttributeContainer;
import com.tyron.builder.api.capabilities.Capability;

import java.util.List;

/**
 * Represents some opaque criteria used to select a component instance during dependency resolution. Various sub-interfaces
 * expose specific details about the criteria.
 *
 * @since 1.10
 */
//@UsedByScanPlugin
public interface ComponentSelector {
    /**
     * Returns a human-consumable display name for this selector.
     *
     * @return Display name
     * @since 1.10
     */
    String getDisplayName();

    /**
     * Checks if selector matches component identifier.
     *
     * @param identifier Component identifier
     * @return if this selector matches exactly the given component identifier.
     * @since 1.10
     */
    boolean matchesStrictly(ComponentIdentifier identifier);

    /**
     * The attributes of the module to select the component from. The attributes only include
     * selector specific attributes. This means it typically doesn't include any consumer specific attribute.
     *
     * @return the attributes
     *
     * @since 4.9
     */
    AttributeContainer getAttributes();

    /**
     * The requested capabilities.
     * @return the requested capabilities. If returning an empty list, the implicit capability will be used.
     *
     * @since 5.3
     */
    List<Capability> getRequestedCapabilities();
}
