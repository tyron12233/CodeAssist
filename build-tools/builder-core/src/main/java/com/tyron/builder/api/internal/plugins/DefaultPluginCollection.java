package com.tyron.builder.api.internal.plugins;

import groovy.lang.Closure;
import com.tyron.builder.api.Action;
import com.tyron.builder.api.Plugin;
import com.tyron.builder.api.internal.CollectionCallbackActionDecorator;
import com.tyron.builder.api.internal.DefaultDomainObjectSet;
import com.tyron.builder.api.internal.collections.CollectionFilter;
import com.tyron.builder.api.plugins.PluginCollection;
import com.tyron.builder.util.Predicates;

import java.util.Collection;
import java.util.function.Predicate;

@SuppressWarnings("deprecation") // Something is weird with the hierarchy of 'add(T)', the implementation inherited from DefaultDomainObjectSet is used internally in DefaultPluginContainer.pluginAdded() but at the same time PluginCollection.add(T) is deprecated
class DefaultPluginCollection<T extends Plugin> extends DefaultDomainObjectSet<T> implements PluginCollection<T> {
    DefaultPluginCollection(Class<T> type, CollectionCallbackActionDecorator decorator) {
        super(type, decorator);
    }

    private DefaultPluginCollection(DefaultPluginCollection<? super T> collection, CollectionFilter<T> filter) {
        super(collection, filter);
    }

    @Override
    protected <S extends T> DefaultPluginCollection<S> filtered(CollectionFilter<S> filter) {
        return new DefaultPluginCollection<S>(this, filter);
    }

    @Override
    public boolean addAll(Collection<? extends T> c) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean remove(Object o) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean removeAll(Collection<?> c) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void clear() {
        throw new UnsupportedOperationException();
    }

    @Override
    public <S extends T> PluginCollection<S> withType(Class<S> type) {
        return filtered(createFilter(type));
    }

    @Override
    public PluginCollection<T> matching(Predicate<? super T> spec) {
        return filtered(createFilter(spec));
    }

    @Override
    public PluginCollection<T> matching(Closure spec) {
        return matching(Predicates.<T>convertClosureToSpec(spec));
    }

    @Override
    public Action<? super T> whenPluginAdded(Action<? super T> action) {
        return whenObjectAdded(action);
    }

    @Override
    public void whenPluginAdded(Closure closure) {
        whenObjectAdded(closure);
    }

}
