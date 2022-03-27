package com.tyron.builder.api.internal.provider;

import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableSet;
import com.tyron.builder.api.internal.Cast;
import com.tyron.builder.api.providers.Provider;
import com.tyron.builder.api.providers.SetProperty;

import java.util.Set;
import java.util.function.Supplier;

import javax.annotation.Nullable;

public class DefaultSetProperty<T> extends AbstractCollectionProperty<T, Set<T>> implements SetProperty<T> {
    private static final Supplier<ImmutableCollection.Builder<Object>> FACTORY = new Supplier<ImmutableCollection.Builder<Object>>() {
        @Override
        public ImmutableCollection.Builder<Object> get() {
            return ImmutableSet.builder();
        }
    };
    public DefaultSetProperty(PropertyHost host, Class<T> elementType) {
        super(host, Set.class, elementType, Cast.uncheckedNonnullCast(FACTORY));
    }

    @Override
    protected Set<T> emptyCollection() {
        return ImmutableSet.of();
    }

    @Override
    public Class<?> publicType() {
        return SetProperty.class;
    }

    @Override
    public int getFactoryId() {
        return ManagedFactories.SetPropertyManagedFactory.FACTORY_ID;
    }

    @Override
    public SetProperty<T> empty() {
        super.empty();
        return this;
    }

    @Override
    public SetProperty<T> value(@Nullable Iterable<? extends T> elements) {
        super.value(elements);
        return this;
    }

    @Override
    public SetProperty<T> value(Provider<? extends Iterable<? extends T>> provider) {
        super.value(provider);
        return this;
    }

    @Override
    public SetProperty<T> convention(Iterable<? extends T> elements) {
        super.convention(elements);
        return this;
    }

    @Override
    public SetProperty<T> convention(Provider<? extends Iterable<? extends T>> provider) {
        super.convention(provider);
        return this;
    }
}