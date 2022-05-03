package com.tyron.builder.api.internal.artifacts;

//import com.tyron.builder.api.internal.artifacts.transform.TransformationStep;
import com.tyron.builder.api.internal.attributes.AttributeContainerInternal;

/**
 * Registration of an artifact transform.
 */
public interface ArtifactTransformRegistration {
    /**
     * Attributes that match the variant that is consumed.
     */
    AttributeContainerInternal getFrom();

    /**
     * Attributes that match the variant that is produced.
     */
    AttributeContainerInternal getTo();

    /**
     * Transformation for artifacts of the variant.
     */
//    TransformationStep getTransformationStep();
}
