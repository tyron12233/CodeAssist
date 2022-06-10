package org.gradle.internal.instantiation;

import org.gradle.api.Describable;
import org.gradle.internal.reflect.Instantiator;
import org.gradle.api.reflect.ObjectInstantiationException;

public interface InstanceGenerator extends Instantiator {
    /**
     * Create a new instance of T with the given display name, using {@code parameters} as the construction parameters.
     *
     * @throws ObjectInstantiationException On failure to create the new instance.
     */
    <T> T newInstanceWithDisplayName(Class<? extends T> type, Describable displayName, Object... parameters) throws ObjectInstantiationException;
}
