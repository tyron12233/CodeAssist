package com.tyron.builder.api.component;

import java.util.Set;

/**
 * Represents a {@link SoftwareComponent} that provides one or more mutually exclusive children, or variants.
 *
 * @since 4.3
 */
public interface ComponentWithVariants extends SoftwareComponent {
    Set<? extends SoftwareComponent> getVariants();
}
