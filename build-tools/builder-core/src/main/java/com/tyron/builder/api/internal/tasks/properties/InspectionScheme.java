package com.tyron.builder.api.internal.tasks.properties;

/**
 * A scheme, or strategy, for inspecting object graphs.
 *
 * <p>Instances are created using a {@link InspectionSchemeFactory}.</p>
 */
public interface InspectionScheme {
    PropertyWalker getPropertyWalker();

    TypeMetadataStore getMetadataStore();
}