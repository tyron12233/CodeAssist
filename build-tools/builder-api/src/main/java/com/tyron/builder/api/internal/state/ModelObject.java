package com.tyron.builder.api.internal.state;

import com.tyron.builder.api.Describable;
import com.tyron.builder.api.Task;

import org.jetbrains.annotations.Nullable;

/**
 * An object that represents some part of a model. This interface is mixed-in to all generated classes and should
 * not be implemented directly.
 */
public interface ModelObject {
    /**
     * Returns the display name of this object that indicates its identity, if this is known.
     */
    @Nullable
    Describable getModelIdentityDisplayName();

    /**
     * Does this type provide a useful {@link Object#toString()} implementation?
     */
    boolean hasUsefulDisplayName();

    /**
     * Returns the task that owns this object, if any.
     */
    @Nullable
    Task getTaskThatOwnsThisObject();
}
