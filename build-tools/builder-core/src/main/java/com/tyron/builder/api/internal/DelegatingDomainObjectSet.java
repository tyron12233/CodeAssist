package com.tyron.builder.api.internal;

import groovy.lang.Closure;
import com.tyron.builder.api.Action;
import com.tyron.builder.api.DomainObjectCollection;
import com.tyron.builder.api.DomainObjectSet;
import com.tyron.builder.api.provider.Provider;
import com.tyron.builder.util.ConfigureUtil;
import com.tyron.builder.util.Predicates;

import java.util.Collection;
import java.util.Iterator;
import java.util.Set;
import java.util.function.Predicate;

public class DelegatingDomainObjectSet<T> implements DomainObjectSet<T> {
    private final DomainObjectSet<T> backingSet;

    public DelegatingDomainObjectSet(DomainObjectSet<T> backingSet) {
        this.backingSet = backingSet;
    }

    @Override
    public DomainObjectSet<T> matching(Closure spec) {
        return matching(Predicates.convertClosureToSpec(spec));
    }

    @Override
    public DomainObjectSet<T> matching(Predicate<? super T> spec) {
        return backingSet.matching(spec);
    }

    @Override
    public <S extends T> DomainObjectSet<S> withType(Class<S> type) {
        return backingSet.withType(type);
    }

    @Override
    public void all(Action<? super T> action) {
        backingSet.all(action);
    }

    @Override
    public void all(Closure action) {
        all(ConfigureUtil.configureUsing(action));
    }

    @Override
    public void configureEach(Action<? super T> action) {
        backingSet.configureEach(action);
    }

    @Override
    public Action<? super T> whenObjectAdded(Action<? super T> action) {
        return backingSet.whenObjectAdded(action);
    }

    @Override
    public void whenObjectAdded(Closure action) {
        whenObjectAdded(ConfigureUtil.configureUsing(action));
    }

    @Override
    public Action<? super T> whenObjectRemoved(Action<? super T> action) {
        return backingSet.whenObjectRemoved(action);
    }

    @Override
    public void whenObjectRemoved(Closure action) {
        whenObjectRemoved(ConfigureUtil.configureUsing(action));
    }

    @Override
    public <S extends T> DomainObjectCollection<S> withType(Class<S> type, Action<? super S> configureAction) {
        return backingSet.withType(type, configureAction);
    }

    @Override
    public <S extends T> DomainObjectCollection<S> withType(Class<S> type, Closure configureClosure) {
        return withType(type, ConfigureUtil.configureUsing(configureClosure));
    }

    @Override
    public void addLater(Provider<? extends T> provider) {
        backingSet.addLater(provider);
    }

    @Override
    public void addAllLater(Provider<? extends Iterable<T>> provider) {
        backingSet.addAllLater(provider);
    }

    @Override
    public boolean add(T o) {
        return backingSet.add(o);
    }

    @Override
    public boolean addAll(Collection<? extends T> c) {
        return backingSet.addAll(c);
    }

    @Override
    public void clear() {
        backingSet.clear();
    }

    @Override
    public boolean contains(Object o) {
        return backingSet.contains(o);
    }

    @Override
    public boolean containsAll(Collection<?> c) {
        return backingSet.containsAll(c);
    }

    @Override
    public boolean isEmpty() {
        return backingSet.isEmpty();
    }

    @Override
    public Iterator<T> iterator() {
        return backingSet.iterator();
    }

    @Override
    public boolean remove(Object o) {
        return backingSet.remove(o);
    }

    @Override
    public boolean removeAll(Collection<?> c) {
        return backingSet.removeAll(c);
    }

    @Override
    public boolean retainAll(Collection<?> c) {
        return backingSet.retainAll(c);
    }

    @Override
    public int size() {
        return backingSet.size();
    }

    @Override
    public Object[] toArray() {
        return backingSet.toArray();
    }

    @Override
    public <T> T[] toArray(T[] a) {
        return backingSet.toArray(a);
    }

    @Override
    public Set<T> findAll(Closure spec) {
        return backingSet.findAll(spec);
    }
}
