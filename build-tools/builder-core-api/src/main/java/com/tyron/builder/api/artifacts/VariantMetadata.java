package com.tyron.builder.api.artifacts;

import com.tyron.builder.api.Action;
import com.tyron.builder.api.attributes.HasConfigurableAttributes;
import com.tyron.builder.api.capabilities.MutableCapabilitiesMetadata;

/**
 * Represents the metadata of one variant of a component, see {@link ComponentMetadataDetails#withVariant(String, Action)}.
 *
 * @since 4.4
 */
public interface VariantMetadata extends HasConfigurableAttributes<VariantMetadata> {

    /**
     * Register a rule that modifies the dependencies of this variant.
     *
     * @param action the action that performs the dependencies adjustment
     */
    void withDependencies(Action<? super DirectDependenciesMetadata> action);

    /**
     * Register a rule that modifies the dependency constraints of this variant.
     *
     * @param action the action that performs the dependency constraints adjustment
     * @since 4.5
     */
    void withDependencyConstraints(Action<? super DependencyConstraintsMetadata> action);

    /**
     * Register a rule that modifies the capabilities of this variant.
     *
     * @param action the action that performs the capabilities adjustment
     * @since 4.7
     */
    void withCapabilities(Action<? super MutableCapabilitiesMetadata> action);

    /**
     * Register a rule that modifies the artifacts of this variant.
     *
     * @param action the action that performs the files adjustment
     * @since 6.0
     */
    void withFiles(Action<? super MutableVariantFilesMetadata> action);
}
