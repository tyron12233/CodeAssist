package com.tyron.builder.api;

import com.tyron.builder.api.internal.rules.NamedDomainObjectFactoryRegistry;
import com.tyron.builder.api.model.ObjectFactory;

import groovy.lang.Closure;

/**
 * A {@link PolymorphicDomainObjectContainer} that can be extended at runtime to
 * create elements of new types.
 *
 * <p>You can create an instance of this type using the factory method {@link ObjectFactory#polymorphicDomainObjectContainer(Class)}.</p>
 *
 * @param <T> the (base) container element type
 */
public interface ExtensiblePolymorphicDomainObjectContainer<T> extends PolymorphicDomainObjectContainer<T>, NamedDomainObjectFactoryRegistry<T> {
    /**
     * Registers a factory for creating elements of the specified type. Typically, the specified type
     * is an interface type.
     *
     * @param type the type of objects created by the factory
     * @param factory the factory to register
     * @param <U> the type of objects created by the factory
     *
     * @throws IllegalArgumentException if the specified type is not a subtype of the container element type
     */
    @Override
    <U extends T> void registerFactory(Class<U> type, NamedDomainObjectFactory<? extends U> factory);

    /**
     * Registers a factory for creating elements of the specified type.
     * Typically, the specified type is an interface type.
     *
     * @param type the type of objects created by the factory
     * @param factory the factory to register
     * @param <U> the type of objects created by the factory
     *
     * @throws IllegalArgumentException if the specified type is not a subtype of the container element type
     */
    <U extends T> void registerFactory(Class<U> type, final Closure<? extends U> factory);

    /**
     * Registers a binding from the specified "public" domain object type to the specified implementation type.
     * Whenever the container is asked to create an element with the binding's public type, it will instantiate
     * the binding's implementation type. If the implementation type has a constructor annotated with
     * {@link javax.inject.Inject}, its arguments will be injected.
     *
     * <p>The implementation type may also be an interface that has a read-only {@code name} property of type String,
     * and is otherwise empty or consists entirely of managed properties.</p>
     *
     * <p>In general, registering a binding is preferable over implementing and registering a factory.
     *
     * @param type a public domain object type
     * @param implementationType the corresponding implementation type
     * @param <U> a public domain object type
     */
    <U extends T> void registerBinding(Class<U> type, final Class<? extends U> implementationType);
}
