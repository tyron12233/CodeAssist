package com.tyron.builder.api.internal.artifacts.transform;

import com.tyron.builder.api.Action;
import com.tyron.builder.api.Describable;

/**
 * The internal API equivalent of {@link com.tyron.builder.api.artifacts.transform.TransformAction}, which is also aware of our cache infrastructure.
 *
 * This can encapsulate a single transformation step using a single transformer or a chain of transformation steps.
 */
public interface Transformation extends Describable {

    boolean endsWith(Transformation otherTransform);

    int stepsCount();

    /**
     * Whether the transformation requires upstream dependencies of the transformed artifact to be injected.
     */
    boolean requiresDependencies();

    /**
     * Extract the transformation steps from this transformation.
     */
//    void visitTransformationSteps(Action<? super TransformationStep> action);
}
