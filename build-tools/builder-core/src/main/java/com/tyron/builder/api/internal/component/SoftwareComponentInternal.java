package com.tyron.builder.api.internal.component;

import com.tyron.builder.api.component.SoftwareComponent;

import java.util.Set;

/**
 * This will be replaced by {@link com.tyron.builder.api.component.ComponentWithVariants} and other public APIs.
 */
public interface SoftwareComponentInternal extends SoftwareComponent {
    Set<? extends UsageContext> getUsages();
}
