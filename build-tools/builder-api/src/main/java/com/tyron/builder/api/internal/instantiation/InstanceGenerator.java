package com.tyron.builder.api.internal.instantiation;

import com.tyron.builder.api.Describable;
import com.tyron.builder.api.internal.reflect.Instantiator;
import com.tyron.builder.api.reflect.ObjectInstantiationException;

public interface InstanceGenerator extends Instantiator {
    /**
     * Create a new instance of T with the given display name, using {@code parameters} as the construction parameters.
     *
     * @throws ObjectInstantiationException On failure to create the new instance.
     */
    <T> T newInstanceWithDisplayName(Class<? extends T> type, Describable displayName, Object... parameters) throws ObjectInstantiationException;
}
