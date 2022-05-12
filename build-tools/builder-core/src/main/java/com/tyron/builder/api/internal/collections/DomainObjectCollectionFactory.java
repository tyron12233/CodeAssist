package com.tyron.builder.api.internal.collections;

import com.tyron.builder.api.DomainObjectCollection;
import com.tyron.builder.api.DomainObjectSet;
import com.tyron.builder.api.ExtensiblePolymorphicDomainObjectContainer;
import com.tyron.builder.api.NamedDomainObjectContainer;
import com.tyron.builder.api.NamedDomainObjectFactory;
import com.tyron.builder.api.NamedDomainObjectList;
import com.tyron.builder.api.NamedDomainObjectSet;
import com.tyron.builder.api.internal.CompositeDomainObjectSet;

import groovy.lang.Closure;

public interface DomainObjectCollectionFactory {
    /**
     * Creates a {@link NamedDomainObjectContainer} for managing named objects of the specified type.
     *
     * Note that this method is here becaue {@link org.gradle.api.Project#container(Class)} cannot decorate the elements because of backwards compatibility.
     */
    <T> NamedDomainObjectContainer<T> newNamedDomainObjectContainerUndecorated(Class<T> elementType);

    /**
     * Creates a {@link NamedDomainObjectContainer} for managing named objects of the specified type.
     */
    <T> NamedDomainObjectContainer<T> newNamedDomainObjectContainer(Class<T> elementType);

    /**
     * Creates a {@link NamedDomainObjectContainer} for managing named objects of the specified type created with the given factory.
     */
    <T> NamedDomainObjectContainer<T> newNamedDomainObjectContainer(Class<T> elementType, NamedDomainObjectFactory<T> factory);

    /**
     * Creates a {@link NamedDomainObjectContainer} for managing named objects of the specified type. The given closure is used to create object instances. The name of the instance to be created is passed as a parameter to the closure.
     */
    <T> NamedDomainObjectContainer<T> newNamedDomainObjectContainer(Class<T> type, Closure factoryClosure);

    /**
     * Creates a {@link DomainObjectSet} for managing objects of the specified type.
     */
    <T> DomainObjectSet<T> newDomainObjectSet(Class<T> elementType);

    <T> NamedDomainObjectSet<T> newNamedDomainObjectSet(Class<T> elementType);

    <T> NamedDomainObjectList<T> newNamedDomainObjectList(Class<T> elementType);

    /**
     * Creates a {@link CompositeDomainObjectSet} for managing a collection of {@link DomainObjectCollection} of the specified type.
     */
    <T> CompositeDomainObjectSet<T> newDomainObjectSet(Class<T> elementType, DomainObjectCollection<? extends T> collection);

    <T> ExtensiblePolymorphicDomainObjectContainer<T> newPolymorphicDomainObjectContainer(Class<T> elementType);
}
