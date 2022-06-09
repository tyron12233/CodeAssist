package com.tyron.builder.api.artifacts.ivy;

import javax.annotation.Nullable;

/**
 * The metadata about an Ivy module that acts as an input to a component metadata rule.
 */
public interface IvyModuleDescriptor {
    /***
     * Returns the branch attribute of the info element in this descriptor.
     *
     * @return the branch for this descriptor, or null if no branch was declared in the descriptor.
     */
    @Nullable
    String getBranch();

    /**
     * Returns the status attribute of the info element in this descriptor.  Note that this <i>always</i> represents
     * the status from the ivy.xml for this module.  It is not affected by changes to the status made via
     * the {@link com.tyron.builder.api.artifacts.ComponentMetadataDetails} interface in a component metadata rule.
     *
     * @return the status for this descriptor
     */
    String getIvyStatus();

    /**
     * Returns an {@link com.tyron.builder.api.artifacts.ivy.IvyExtraInfo} representing the "extra" info declared
     * in this descriptor.
     * <p>
     * The extra info is the set of all non-standard subelements of the <em>info</em> element.
     *
     * @return an {@link com.tyron.builder.api.artifacts.ivy.IvyExtraInfo} representing the extra info declared in this descriptor
     */
    IvyExtraInfo getExtraInfo();
}
