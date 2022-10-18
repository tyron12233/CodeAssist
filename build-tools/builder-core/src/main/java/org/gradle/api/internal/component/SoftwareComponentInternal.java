package org.gradle.api.internal.component;

import org.gradle.api.component.SoftwareComponent;

import java.util.Set;

/**
 * This will be replaced by {@link org.gradle.api.component.ComponentWithVariants} and other public APIs.
 */
public interface SoftwareComponentInternal extends SoftwareComponent {
    Set<? extends UsageContext> getUsages();
}
