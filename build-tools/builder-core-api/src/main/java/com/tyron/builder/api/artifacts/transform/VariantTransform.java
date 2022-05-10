package com.tyron.builder.api.artifacts.transform;

import com.tyron.builder.api.Action;
import com.tyron.builder.api.ActionConfiguration;
import com.tyron.builder.api.attributes.AttributeContainer;

/**
 * Registration of an variant transform.
 *
 * @since 3.5
 * @deprecated Use {@link TransformSpec} instead.
 */
@Deprecated
public interface VariantTransform {
    /**
     * Attributes that match the variant that is consumed.
     */
    AttributeContainer getFrom();

    /**
     * Attributes that match the variant that is produced.
     */
    AttributeContainer getTo();

    /**
     * Action to transform artifacts for this variant transform.
     *
     * <p>An instance of the specified type is created for each file that is to be transformed. The class should provide a public zero-args constructor.</p>
     */
    void artifactTransform(Class<? extends ArtifactTransform> type);

    /**
     * Action to transform artifacts for this variant transform, potentially supplying some configuration to inject into the transform.
     *
     * <p>An instance of the specified type is created for each file that is to be transformed. The class should provide a public constructor that accepts the provided configuration.</p>
     */
    void artifactTransform(Class<? extends ArtifactTransform> type, Action<? super ActionConfiguration> configAction);
}
