package com.tyron.builder.api.internal.reflect;

import com.tyron.builder.api.reflect.ObjectInstantiationException;

/**
 * An object that can create new instances of various types. An {@code Instantiator}, depending on its implementation and configuration, may provide
 * a number of capabilities. Some examples:
 *
 * <ul>
 * <li>An implementation may decorate the instances in some fashion, for example to mix in the Groovy DSL, and so may return a subclass of the requested type.
 *
 * <li>An implementation may accept abstract classes or interfaces and provide implementations for the missing pieces, for example providing an implementation of ExtensionAware.
 *
 * <li>An implementation may provide injection of services and other dependencies into the instances it creates, for example exposing services via a getter method.
 *
 * </ul>
 *
 * <p>An implementation is not required to support any of these features. Implementations must be thread-safe.
 *
 * <p>A service of this type is available in all scopes. However, the recommended way to receive an {@code Instantiator} is via a {@link org.gradle.internal.instantiation.InstantiatorFactory}.</p>
 */
public interface Instantiator {

    /**
     * Create a new instance of T, using {@code parameters} as the construction parameters.
     *
     * @throws ObjectInstantiationException On failure to create the new instance.
     */
    <T> T newInstance(Class<? extends T> type, Object... parameters) throws ObjectInstantiationException;

}

