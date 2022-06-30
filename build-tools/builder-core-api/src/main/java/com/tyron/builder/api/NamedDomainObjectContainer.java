package com.tyron.builder.api;

import com.tyron.builder.api.provider.Provider;
import com.tyron.builder.util.Configurable;

import groovy.lang.Closure;

/**
 * <p>A named domain object container is a specialization of {@link NamedDomainObjectSet} that adds the ability to create
 * instances of the element type.</p>
 *
 * <p>Implementations may use different strategies for creating new object instances.</p>
 *
 * <p>Note that a container is an implementation of {@link java.util.SortedSet}, which means that the container is guaranteed
 * to only contain elements with unique names within this container. Furthermore, items are ordered by their name.</p>
 *
 * <p>You can create an instance of this type using the factory method {@link ObjectFactory#domainObjectContainer(Class)}.</p>
 *
 * @param <T> The type of objects in this container.
 */
public interface NamedDomainObjectContainer<T> extends NamedDomainObjectSet<T>, Configurable<NamedDomainObjectContainer<T>> {

    /**
     * Creates a new item with the given name, adding it to this container.
     *
     * @param name The name to assign to the created object
     * @return The created object. Never null.
     * @throws InvalidUserDataException if an object with the given name already exists in this container.
     */
    T create(String name) throws InvalidUserDataException;

    /**
     * Looks for an item with the given name, creating and adding it to this container if it does not exist.
     *
     * @param name The name to find or assign to the created object
     * @return The found or created object. Never null.
     */
    T maybeCreate(String name);

    /**
     * Creates a new item with the given name, adding it to this container, then configuring it with the given closure.
     *
     * @param name The name to assign to the created object
     * @param configureClosure The closure to configure the created object with
     * @return The created object. Never null.
     * @throws InvalidUserDataException if an object with the given name already exists in this container.
     */
    T create(String name, Closure configureClosure) throws InvalidUserDataException;

    /**
     * Creates a new item with the given name, adding it to this container, then configuring it with the given action.
     *
     * @param name The name to assign to the created object
     * @param configureAction The action to configure the created object with
     * @return The created object. Never null.
     * @throws InvalidUserDataException if an object with the given name already exists in this container.
     */
    T create(String name, Action<? super T> configureAction) throws InvalidUserDataException;

    /**
     * <p>Allows the container to be configured, creating missing objects as they are referenced.</p>
     *
     * <p>TODO: example usage</p>
     *
     * @param configureClosure The closure to configure this container with
     * @return This.
     */
    @Override
    NamedDomainObjectContainer<T> configure(Closure configureClosure);

    /**
     * Defines a new object, which will be created and configured when it is required. An object is 'required' when the object is located using query methods such as {@link NamedDomainObjectCollection#getByName(java.lang.String)} or when {@link Provider#get()} is called on the return value of this method.
     *
     * <p>It is generally more efficient to use this method instead of {@link #create(java.lang.String, org.gradle.api.Action)} or {@link #create(java.lang.String)}, as those methods will eagerly create and configure the object, regardless of whether that object is required for the current build or not. This method, on the other hand, will defer creation and configuration until required.</p>
     *
     * @param name The name of the object.
     * @param configurationAction The action to run to configure the object. This action runs when the object is required.
     * @return A {@link Provider} that whose value will be the object, when queried.
     * @throws InvalidUserDataException If a object with the given name already exists in this project.
     * @since 4.10
     */
    NamedDomainObjectProvider<T> register(String name, Action<? super T> configurationAction) throws InvalidUserDataException;

    /**
     * Defines a new object, which will be created when it is required. A object is 'required' when the object is located using query methods such as {@link NamedDomainObjectCollection#getByName(java.lang.String)} or when {@link Provider#get()} is called on the return value of this method.
     *
     * <p>It is generally more efficient to use this method instead of {@link #create(java.lang.String)}, as that method will eagerly create the object, regardless of whether that object is required for the current build or not. This method, on the other hand, will defer creation until required.</p>
     *
     * @param name The name of the object.
     * @return A {@link Provider} that whose value will be the object, when queried.
     * @throws InvalidUserDataException If a object with the given name already exists in this project.
     * @since 4.10
     */
    NamedDomainObjectProvider<T> register(String name) throws InvalidUserDataException;
}
