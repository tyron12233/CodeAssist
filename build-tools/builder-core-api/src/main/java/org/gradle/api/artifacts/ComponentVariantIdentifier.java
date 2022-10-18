package org.gradle.api.artifacts;

import org.gradle.api.artifacts.component.ComponentIdentifier;

/**
 * Identifies a variant of a component by module identifier and variant name.
 *
 * @since 6.0
 */
public interface ComponentVariantIdentifier {

    /**
     * Returns the component identifier.
     */
    ComponentIdentifier getId();

    /**
     * Returns the variant name.
     */
    String getVariantName();
}
