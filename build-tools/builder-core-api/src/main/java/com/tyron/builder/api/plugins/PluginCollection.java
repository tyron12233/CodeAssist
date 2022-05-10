package com.tyron.builder.api.plugins;

import com.tyron.builder.api.Action;
import com.tyron.builder.api.DomainObjectSet;
import com.tyron.builder.api.Plugin;

import java.util.Collection;
import java.util.Set;
import java.util.function.Predicate;

import groovy.lang.Closure;

/**
 * <p>A {@code PluginCollection} represents a collection of {@link Plugin} instances.</p>
 *
 * @param <T> The type of plugins which this collection contains.
 */
public interface PluginCollection<T extends Plugin> extends DomainObjectSet<T> {
    /**
     * {@inheritDoc}
     */
    @Override
    PluginCollection<T> matching(Predicate<? super T> spec);

    /**
     * {@inheritDoc}
     */
    @Override
    PluginCollection<T> matching(Closure closure);

    /**
     * {@inheritDoc}
     */
    @Override
    <S extends T> PluginCollection<S> withType(Class<S> type);

    /**
     * Adds an {@code Action} to be executed when a plugin is added to this collection.
     *
     * @param action The action to be executed
     * @return the supplied action
     */
    @SuppressWarnings("UnusedDeclaration")
    Action<? super T> whenPluginAdded(Action<? super T> action);

    /**
     * Adds a closure to be called when a plugin is added to this collection. The plugin is passed to the closure as the
     * parameter.
     *
     * @param closure The closure to be called
     */
    @SuppressWarnings("UnusedDeclaration")
    void whenPluginAdded(Closure closure);

    /**
     * Unsupported.
     * @deprecated Use {@link PluginManager#apply(Class)} instead.
     */
    @Override
    @Deprecated
    boolean add(T plugin);

    /**
     * Unsupported.
     * @deprecated Use {@link PluginManager#apply(Class)} instead.
     */
    @Deprecated
    @Override
    boolean addAll(Collection<? extends T> c);

    /**
     * Unsupported.
     * @deprecated plugins cannot be removed.
     */
    @Override
    @Deprecated
    boolean remove(Object o);

    /**
     * Unsupported.
     * @deprecated plugins cannot be removed.
     */
    @Override
    @Deprecated
    boolean removeAll(Collection<?> c);

    /**
     * Unsupported.
     * @deprecated plugins cannot be removed.
     */
    @Override
    @Deprecated
    void clear();
}
