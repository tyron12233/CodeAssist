package com.tyron.builder.api.internal.state;

import com.tyron.builder.api.internal.DisplayName;

import org.jetbrains.annotations.Nullable;

/**
 * Represents an object that may be owned by some model object. This is mixed-in to generated classes and may
 * also be implemented directly.
 */
public interface OwnerAware {
    /**
     * Notifies this object that it now has an owner associated with it.
     *
     * @param owner The owner object, if any.
     * @param displayName The display name for this object.
     */
    void attachOwner(@Nullable ModelObject owner, DisplayName displayName);
}