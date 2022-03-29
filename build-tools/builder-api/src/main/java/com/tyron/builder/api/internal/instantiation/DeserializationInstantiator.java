package com.tyron.builder.api.internal.instantiation;

import com.tyron.builder.api.reflect.ObjectInstantiationException;

/**
 * Creates instance of objects in preparation for deserialization of their state.
 */
public interface DeserializationInstantiator {
    /**
     * Creates an instance of the given type without invoking its constructor. Invokes the constructor of the given base class.
     *
     * @throws ObjectInstantiationException On failure to create the new instance.
     */
    <T> T newInstance(Class<T> implType, Class<? super T> baseClass) throws ObjectInstantiationException;
}
