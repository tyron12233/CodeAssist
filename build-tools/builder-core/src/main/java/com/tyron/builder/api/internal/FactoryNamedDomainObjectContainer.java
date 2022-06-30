package com.tyron.builder.api.internal;

import groovy.lang.Closure;
import com.tyron.builder.api.Action;
import com.tyron.builder.api.Named;
import com.tyron.builder.api.NamedDomainObjectFactory;
import com.tyron.builder.api.Namer;
import com.tyron.builder.api.internal.collections.CollectionFilter;
import com.tyron.builder.internal.Cast;
import com.tyron.builder.internal.reflect.Instantiator;

public class FactoryNamedDomainObjectContainer<T> extends AbstractNamedDomainObjectContainer<T> {

    private final NamedDomainObjectFactory<T> factory;
    private final MutationGuard crossProjectConfiguratorMutationGuard;

    /**
     * <p>Creates a container that instantiates using the given factory.<p>
     *
     * @param type The concrete type of element in the container (must implement {@link Named})
     * @param instantiator The instantiator to use to create any other collections based on this one
     * @param factory The factory responsible for creating new instances on demand
     * @param collectionCallbackActionDecorator the decorator for collection callback action execution
     */
    public FactoryNamedDomainObjectContainer(Class<T> type, Instantiator instantiator, NamedDomainObjectFactory<T> factory, CollectionCallbackActionDecorator collectionCallbackActionDecorator) {
        this(type, instantiator, Named.Namer.forType(type), factory, MutationGuards.identity(), collectionCallbackActionDecorator);
    }

    /**
     * <p>Creates a container that instantiates using the given factory.<p>
     *
     * @param type The concrete type of element in the container
     * @param instantiator The instantiator to use to create any other collections based on this one
     * @param namer The naming strategy to use
     * @param factory The factory responsible for creating new instances on demand
     */
    public FactoryNamedDomainObjectContainer(Class<T> type, Instantiator instantiator, Namer<? super T> namer, NamedDomainObjectFactory<T> factory, MutationGuard crossProjectConfiguratorMutationGuard, CollectionCallbackActionDecorator collectionCallbackActionDecorator) {
        super(type, instantiator, namer, collectionCallbackActionDecorator);
        this.factory = factory;
        this.crossProjectConfiguratorMutationGuard = crossProjectConfiguratorMutationGuard;
    }

    /**
     * <p>Creates a container that instantiates using the given factory.<p>
     *
     * @param type The concrete type of element in the container (must implement {@link Named})
     * @param instantiator The instantiator to use to create any other collections based on this one
     * @param factoryClosure The closure responsible for creating new instances on demand
     */
    public FactoryNamedDomainObjectContainer(Class<T> type, Instantiator instantiator, final Closure<?> factoryClosure, CollectionCallbackActionDecorator collectionCallbackActionDecorator) {
        this(type, instantiator, Named.Namer.forType(type), factoryClosure, MutationGuards.identity(), collectionCallbackActionDecorator);
    }

    /**
     * <p>Creates a container that instantiates using the given factory.<p>
     *
     * @param type The concrete type of element in the container
     * @param instantiator The instantiator to use to create any other collections based on this one
     * @param namer The naming strategy to use
     * @param factoryClosure The factory responsible for creating new instances on demand
     */
    public FactoryNamedDomainObjectContainer(Class<T> type, Instantiator instantiator, Namer<? super T> namer, final Closure<?> factoryClosure, MutationGuard mutationGuard, CollectionCallbackActionDecorator collectionCallbackActionDecorator) {
        this(type, instantiator, namer, new ClosureObjectFactory<>(type, factoryClosure), mutationGuard, collectionCallbackActionDecorator);
    }

    @Override
    protected <S extends T> DefaultNamedDomainObjectSet<S> filtered(CollectionFilter<S> filter) {
        return Cast.uncheckedNonnullCast(getInstantiator().newInstance(DefaultNamedDomainObjectSet.class, this, filter, getInstantiator(), getNamer(), crossProjectConfiguratorMutationGuard));
    }

    @Override
    protected <I extends T> Action<? super I> withMutationDisabled(Action<? super I> action) {
        return crossProjectConfiguratorMutationGuard.withMutationDisabled(super.withMutationDisabled(action));
    }

    @Override
    protected T doCreate(String name) {
        return factory.create(name);
    }

    private static class ClosureObjectFactory<T> implements NamedDomainObjectFactory<T> {
        private final Class<T> type;
        private final Closure<?> factoryClosure;

        public ClosureObjectFactory(Class<T> type, Closure<?> factoryClosure) {
            this.type = type;
            this.factoryClosure = factoryClosure;
        }

        @Override
        public T create(String name) {
            return type.cast(factoryClosure.call(name));
        }
    }
}
