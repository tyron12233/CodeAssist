package com.tyron.builder.api.transform;

import com.android.annotations.NonNull;
import com.android.annotations.Nullable;
import java.util.Collection;

/**
 * An invocation object used to pass of pertinent information for a
 * {@link Transform#transform(TransformInvocation)} call.
 * @deprecated
 */
@Deprecated
public interface TransformInvocation {

    /**
     * Returns the context in which the transform is run.
     * @return the context in which the transform is run.
     */
    @NonNull
    Context getContext();

    /**
     * Returns the inputs/outputs of the transform.
     * @return the inputs/outputs of the transform.
     */
    @NonNull
    Collection<TransformInput> getInputs();

    /**
     * Returns the referenced-only inputs which are not consumed by this transformation.
     * @return the referenced-only inputs.
     */
    @NonNull Collection<TransformInput> getReferencedInputs();
    /**
     * Returns the list of secondary file changes since last. Only secondary files that this
     * transform can handle incrementally will be part of this change set.
     * @return the list of changes impacting a {@link SecondaryInput}
     */
    @NonNull Collection<SecondaryInput> getSecondaryInputs();

    /**
     * Returns the output provider allowing to create content.
     * @return he output provider allowing to create content.
     */
    @Nullable
    TransformOutputProvider getOutputProvider();


    /**
     * Indicates whether the transform execution is incremental.
     * @return true for an incremental invocation, false otherwise.
     */
    boolean isIncremental();
}
