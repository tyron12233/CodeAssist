package org.gradle.api.artifacts;

import org.gradle.api.Action;

/**
 * A component metadata rule is responsible for providing the initial metadata of a component
 * from a remote repository, in place of parsing the descriptor. Users may implement a provider
 * to make dependency resolution faster.
 *
 * @since 4.0
 */
public interface ComponentMetadataSupplier extends Action<ComponentMetadataSupplierDetails> {
    /**
     * Supply metadata for a component.
     *
     * @param details the supplier details
     */
    @Override
    void execute(ComponentMetadataSupplierDetails details);
}
