package com.tyron.builder.api.internal.artifacts;

import com.tyron.builder.api.Action;
import com.tyron.builder.api.artifacts.transform.TransformAction;
import com.tyron.builder.api.artifacts.transform.TransformParameters;
import com.tyron.builder.api.artifacts.transform.TransformSpec;

import java.util.List;

public interface VariantTransformRegistry {

    /**
     * Register an artifact transformation.
     *
     * @see com.tyron.builder.api.artifacts.transform.VariantTransform
     * @deprecated Use {@link #registerTransform(Class, Action)} instead
     */
    @Deprecated
    @SuppressWarnings("deprecation")
    void registerTransform(Action<? super com.tyron.builder.api.artifacts.transform.VariantTransform> registrationAction);

    /**
     * Register an artifact transformation.
     *
     * @see TransformAction
     */
    <T extends TransformParameters> void registerTransform(Class<? extends TransformAction<T>> actionType, Action<? super TransformSpec<T>> registrationAction);

    List<ArtifactTransformRegistration> getTransforms();
}
