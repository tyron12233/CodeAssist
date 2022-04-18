package com.tyron.builder.api.internal.provider;


import com.tyron.builder.api.providers.HasConfigurableValue;

public interface HasConfigurableValueInternal extends HasConfigurableValue {
    /**
     * Same semantics as {@link HasConfigurableValue#finalizeValue()}, but finalizes the value of this object lazily, when the value is queried.
     * Implementations may then fail on subsequent changes, or generate a deprecation warning and ignore changes.
     */
    void implicitFinalizeValue();
}