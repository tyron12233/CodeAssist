package com.tyron.builder.api.internal;

import groovy.lang.Closure;
import com.tyron.builder.api.Action;
import com.tyron.builder.api.DomainObjectCollection;
import com.tyron.builder.api.NamedDomainObjectProvider;
import com.tyron.builder.api.InvalidUserDataException;
import com.tyron.builder.api.NamedDomainObjectCollectionSchema;
import com.tyron.builder.api.NamedDomainObjectContainer;
import com.tyron.builder.api.NamedDomainObjectSet;
import com.tyron.builder.api.Namer;
import com.tyron.builder.api.Rule;
import com.tyron.builder.api.UnknownDomainObjectException;
import com.tyron.builder.api.provider.Provider;
import com.tyron.builder.internal.metaobject.MethodAccess;
import com.tyron.builder.internal.metaobject.MethodMixIn;
import com.tyron.builder.internal.metaobject.PropertyAccess;
import com.tyron.builder.internal.metaobject.PropertyMixIn;
import com.tyron.builder.util.ConfigureUtil;

import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.function.Predicate;

public class TypedDomainObjectContainerWrapper<U> implements NamedDomainObjectContainer<U>, MethodMixIn, PropertyMixIn {
    private final Class<U> type;
    private final AbstractPolymorphicDomainObjectContainer<? super U> parent;
    private final NamedDomainObjectSet<U> delegate;

    public TypedDomainObjectContainerWrapper(Class<U> type, AbstractPolymorphicDomainObjectContainer<? super U> parent) {
        this.parent = parent;
        this.type = type;
        this.delegate = parent.withType(type);
    }

    @Override
    public U create(String name) throws InvalidUserDataException {
        return parent.create(name, type);
    }

    @Override
    public U create(String name, Action<? super U> configureAction) throws InvalidUserDataException {
        return parent.create(name, type, configureAction);
    }

    @Override
    public U create(String name, Closure configureClosure) throws InvalidUserDataException {
        return parent.create(name, type, ConfigureUtil.configureUsing(configureClosure));
    }

    @Override
    public U maybeCreate(String name) {
        return parent.maybeCreate(name, type);
    }

    @Override
    public MethodAccess getAdditionalMethods() {
        return parent.getAdditionalMethods();
    }

    @Override
    public PropertyAccess getAdditionalProperties() {
        return parent.getAdditionalProperties();
    }

    @Override
    public NamedDomainObjectContainer<U> configure(Closure configureClosure) {
        NamedDomainObjectContainerConfigureDelegate delegate = new NamedDomainObjectContainerConfigureDelegate(configureClosure, this);
        return ConfigureUtil.configureSelf(configureClosure, this, delegate);
    }

    @Override
    public NamedDomainObjectProvider<U> register(String name, Action<? super U> configurationAction) throws InvalidUserDataException {
        return parent.register(name, type, configurationAction);
    }

    @Override
    public NamedDomainObjectProvider<U> register(String name) throws InvalidUserDataException {
        return parent.register(name, type);
    }

    @Override
    public Set<U> findAll(Closure spec) {
        return delegate.findAll(spec);
    }

    @Override
    public NamedDomainObjectSet<U> matching(Closure spec) {
        return delegate.matching(spec);
    }

    @Override
    public NamedDomainObjectProvider<U> named(String name) throws UnknownDomainObjectException {
        return delegate.named(name);
    }

    @Override
    public NamedDomainObjectProvider<U> named(String name, Action<? super U> configurationAction) throws UnknownDomainObjectException {
        return delegate.named(name, configurationAction);
    }

    @Override
    public <S extends U> NamedDomainObjectProvider<S> named(String name, Class<S> type) throws UnknownDomainObjectException {
        return delegate.named(name, type);
    }

    @Override
    public <S extends U> NamedDomainObjectProvider<S> named(String name, Class<S> type, Action<? super S> configurationAction) throws UnknownDomainObjectException {
        return delegate.named(name, type, configurationAction);
    }

    @Override
    public NamedDomainObjectSet<U> matching(Predicate<? super U> spec) {
        return delegate.matching(spec);
    }

    @Override
    public <S extends U> NamedDomainObjectSet<S> withType(Class<S> type) {
        return delegate.withType(type);
    }

    @Override
    public boolean add(U e) {
        return delegate.add(e);
    }

    @Override
    public void addLater(Provider<? extends U> provider) {
        delegate.addLater(provider);
    }

    @Override
    public void addAllLater(Provider<? extends Iterable<U>> provider) {
        delegate.addAllLater(provider);
    }

    @Override
    public boolean addAll(Collection<? extends U> c) {
        return delegate.addAll(c);
    }

    @Override
    public Rule addRule(String description, Closure ruleAction) {
        return delegate.addRule(description, ruleAction);
    }

    @Override
    public Rule addRule(String description, Action<String> ruleAction) {
        return delegate.addRule(description, ruleAction);
    }

    @Override
    public Rule addRule(Rule rule) {
        return delegate.addRule(rule);
    }

    @Override
    public U findByName(String name) {
        return delegate.findByName(name);
    }

    @Override
    public SortedMap<String, U> getAsMap() {
        return delegate.getAsMap();
    }

    @Override
    public NamedDomainObjectCollectionSchema getCollectionSchema() {
        return delegate.getCollectionSchema();
    }

    @Override
    public U getAt(String name) throws UnknownDomainObjectException {
        return delegate.getAt(name);
    }

    @Override
    public U getByName(String name) throws UnknownDomainObjectException {
        return delegate.getByName(name);
    }

    @Override
    public U getByName(String name, Closure configureClosure) throws UnknownDomainObjectException {
        return delegate.getByName(name, configureClosure);
    }

    @Override
    public U getByName(String name, Action<? super U> configureAction) throws UnknownDomainObjectException {
        return delegate.getByName(name, configureAction);
    }

    @Override
    public Namer<U> getNamer() {
        return delegate.getNamer();
    }

    @Override
    public SortedSet<String> getNames() {
        return delegate.getNames();
    }

    @Override
    public List<Rule> getRules() {
        return delegate.getRules();
    }

    @Override
    public void all(Action<? super U> action) {
        delegate.all(action);
    }

    @Override
    public void all(Closure action) {
        delegate.all(action);
    }

    @Override
    public void configureEach(Action<? super U> action) {
        delegate.configureEach(action);
    }

    @Override
    public Action<? super U> whenObjectAdded(Action<? super U> action) {
        return delegate.whenObjectAdded(action);
    }

    @Override
    public void whenObjectAdded(Closure action) {
        delegate.whenObjectAdded(action);
    }

    @Override
    public Action<? super U> whenObjectRemoved(Action<? super U> action) {
        return delegate.whenObjectRemoved(action);
    }

    @Override
    public void whenObjectRemoved(Closure action) {
        delegate.whenObjectRemoved(action);
    }

    @Override
    public <S extends U> DomainObjectCollection<S> withType(Class<S> type, Action<? super S> configureAction) {
        return delegate.withType(type, configureAction);
    }

    @Override
    public <S extends U> DomainObjectCollection<S> withType(Class<S> type, Closure configureClosure) {
        return delegate.withType(type, configureClosure);
    }

    @Override
    public void clear() {
        delegate.clear();
    }

    @Override
    public boolean contains(Object o) {
        return delegate.contains(o);
    }

    @Override
    public boolean containsAll(Collection<?> c) {
        return delegate.containsAll(c);
    }

    @Override
    public boolean equals(Object o) {
        return delegate.equals(o);
    }

    @Override
    public int hashCode() {
        return delegate.hashCode();
    }

    @Override
    public boolean isEmpty() {
        return delegate.isEmpty();
    }

    @Override
    public Iterator<U> iterator() {
        return delegate.iterator();
    }

    @Override
    public boolean remove(Object o) {
        return delegate.remove(o);
    }

    @Override
    public boolean removeAll(Collection<?> c) {
        return delegate.removeAll(c);
    }

    @Override
    public boolean retainAll(Collection<?> c) {
        return delegate.retainAll(c);
    }

    @Override
    public int size() {
        return delegate.size();
    }

    @Override
    public Object[] toArray() {
        return delegate.toArray();
    }

    @Override
    public <T> T[] toArray(T[] a) {
        return delegate.toArray(a);
    }
}
