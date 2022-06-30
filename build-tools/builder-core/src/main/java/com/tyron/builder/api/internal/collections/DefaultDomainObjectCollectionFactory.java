package com.tyron.builder.api.internal.collections;

import groovy.lang.Closure;
import com.tyron.builder.api.DomainObjectCollection;
import com.tyron.builder.api.DomainObjectSet;
import com.tyron.builder.api.ExtensiblePolymorphicDomainObjectContainer;
import com.tyron.builder.api.NamedDomainObjectContainer;
import com.tyron.builder.api.NamedDomainObjectFactory;
import com.tyron.builder.api.NamedDomainObjectList;
import com.tyron.builder.api.NamedDomainObjectSet;
import com.tyron.builder.api.internal.CollectionCallbackActionDecorator;
import com.tyron.builder.api.internal.CompositeDomainObjectSet;
import com.tyron.builder.api.internal.DefaultDomainObjectSet;
import com.tyron.builder.api.internal.DefaultNamedDomainObjectList;
import com.tyron.builder.api.internal.DefaultNamedDomainObjectSet;
import com.tyron.builder.api.internal.DefaultPolymorphicDomainObjectContainer;
import com.tyron.builder.api.internal.DynamicPropertyNamer;
import com.tyron.builder.api.internal.FactoryNamedDomainObjectContainer;
import com.tyron.builder.api.internal.MutationGuard;
import com.tyron.builder.api.internal.ReflectiveNamedDomainObjectFactory;
import com.tyron.builder.internal.Cast;
import com.tyron.builder.internal.instantiation.InstanceGenerator;
import com.tyron.builder.internal.instantiation.InstantiatorFactory;
import com.tyron.builder.internal.reflect.Instantiator;
import com.tyron.builder.internal.service.ServiceRegistry;

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
