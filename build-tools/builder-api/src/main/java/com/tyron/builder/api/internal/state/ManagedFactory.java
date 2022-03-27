package com.tyron.builder.api.internal.state;


import org.jetbrains.annotations.Nullable;

public interface ManagedFactory {
    /**
     * Creates an instance of a managed object from the given state, if possible.
     */
    @Nullable
    <T> T fromState(Class<T> type, Object state);

    /**
     * Returns an id for this factory that can be used to retrieve it from a {@link ManagedFactoryRegistry}.
     */
    int getId();
}