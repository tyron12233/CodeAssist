package org.gradle.api.internal.collections;

import groovy.lang.Closure;
import org.gradle.api.DomainObjectCollection;
import org.gradle.api.DomainObjectSet;
import org.gradle.api.ExtensiblePolymorphicDomainObjectContainer;
import org.gradle.api.NamedDomainObjectContainer;
import org.gradle.api.NamedDomainObjectFactory;
import org.gradle.api.NamedDomainObjectList;
import org.gradle.api.NamedDomainObjectSet;
import org.gradle.api.internal.CollectionCallbackActionDecorator;
import org.gradle.api.internal.CompositeDomainObjectSet;
import org.gradle.api.internal.DefaultDomainObjectSet;
import org.gradle.api.internal.DefaultNamedDomainObjectList;
import org.gradle.api.internal.DefaultNamedDomainObjectSet;
import org.gradle.api.internal.DefaultPolymorphicDomainObjectContainer;
import org.gradle.api.internal.DynamicPropertyNamer;
import org.gradle.api.internal.FactoryNamedDomainObjectContainer;
import org.gradle.api.internal.MutationGuard;
import org.gradle.api.internal.ReflectiveNamedDomainObjectFactory;
import org.gradle.internal.Cast;
import org.gradle.internal.instantiation.InstanceGenerator;
import org.gradle.internal.instantiation.InstantiatorFactory;
import org.gradle.internal.reflect.Instantiator;
import org.gradle.internal.service.ServiceRegistry;

public class DefaultDomainObjectCollectionFactory implements DomainObjectCollectionFactory {
    private final InstantiatorFactory instantiatorFactory;
    private final ServiceRegistry servicesToInject;
    private final CollectionCallbackActionDecorator collectionCallbackActionDecorator;
    private final MutationGuard mutationGuard;

    public DefaultDomainObjectCollectionFactory(InstantiatorFactory instantiatorFactory, ServiceRegistry servicesToInject, CollectionCallbackActionDecorator collectionCallbackActionDecorator, MutationGuard mutationGuard) {
        this.instantiatorFactory = instantiatorFactory;
        this.servicesToInject = servicesToInject;
        this.collectionCallbackActionDecorator = collectionCallbackActionDecorator;
        this.mutationGuard = mutationGuard;
    }

    @Override
    public <T> NamedDomainObjectContainer<T> newNamedDomainObjectContainerUndecorated(Class<T> elementType) {
        // Do not decorate the elements, for backwards compatibility
        return container(elementType, instantiatorFactory.injectLenient(servicesToInject));
    }

    @Override
    public <T> NamedDomainObjectContainer<T> newNamedDomainObjectContainer(Class<T> elementType) {
        return container(elementType, instantiatorFactory.decorateLenient(servicesToInject));
    }

    private <T> NamedDomainObjectContainer<T> container(Class<T> elementType, InstanceGenerator elementInstantiator) {
        ReflectiveNamedDomainObjectFactory<T> objectFactory = new ReflectiveNamedDomainObjectFactory<T>(elementType, elementInstantiator);
        Instantiator instantiator = instantiatorFactory.decorateLenient();
        return Cast.uncheckedCast(instantiator.newInstance(FactoryNamedDomainObjectContainer.class, elementType, instantiator, new DynamicPropertyNamer(), objectFactory, mutationGuard, collectionCallbackActionDecorator));
    }

    @Override
    public <T> NamedDomainObjectContainer<T> newNamedDomainObjectContainer(Class<T> elementType, NamedDomainObjectFactory<T> factory) {
        Instantiator instantiator = instantiatorFactory.decorateLenient();
        return Cast.uncheckedCast(instantiator.newInstance(FactoryNamedDomainObjectContainer.class, elementType, instantiator, new DynamicPropertyNamer(), factory, mutationGuard, collectionCallbackActionDecorator));
    }

    @Override
    public <T> NamedDomainObjectContainer<T> newNamedDomainObjectContainer(Class<T> type, Closure factoryClosure) {
        Instantiator instantiator = instantiatorFactory.decorateLenient();
        return Cast.uncheckedCast(instantiator.newInstance(FactoryNamedDomainObjectContainer.class, type, instantiator, new DynamicPropertyNamer(), factoryClosure, mutationGuard, collectionCallbackActionDecorator));
    }

    @Override
    public <T> ExtensiblePolymorphicDomainObjectContainer<T> newPolymorphicDomainObjectContainer(Class<T> elementType) {
        Instantiator instantiator = instantiatorFactory.decorateLenient();
        Instantiator elementInstantiator = instantiatorFactory.decorateLenient(servicesToInject);
        return Cast.uncheckedCast(instantiator.newInstance(DefaultPolymorphicDomainObjectContainer.class, elementType, instantiator, elementInstantiator, collectionCallbackActionDecorator));
    }

    @Override
    public <T> DomainObjectSet<T> newDomainObjectSet(Class<T> elementType) {
        Instantiator instantiator = instantiatorFactory.decorateLenient();
        return Cast.uncheckedCast(instantiator.newInstance(DefaultDomainObjectSet.class, elementType, collectionCallbackActionDecorator));
    }

    @Override
    public <T> NamedDomainObjectSet<T> newNamedDomainObjectSet(Class<T> elementType) {
        Instantiator instantiator = instantiatorFactory.decorateLenient();
        return Cast.uncheckedCast(instantiator.newInstance(DefaultNamedDomainObjectSet.class, elementType, instantiator, new DynamicPropertyNamer(), collectionCallbackActionDecorator));
    }

    @Override
    public <T> NamedDomainObjectList<T> newNamedDomainObjectList(Class<T> elementType) {
        Instantiator instantiator = instantiatorFactory.decorateLenient();
        return Cast.uncheckedCast(instantiator.newInstance(DefaultNamedDomainObjectList.class, elementType, instantiator, new DynamicPropertyNamer(), collectionCallbackActionDecorator));
    }

    @Override
    public <T> CompositeDomainObjectSet<T> newDomainObjectSet(Class<T> elementType, DomainObjectCollection<? extends T> collection) {
        return CompositeDomainObjectSet.create(elementType, collectionCallbackActionDecorator, collection);
    }
}
