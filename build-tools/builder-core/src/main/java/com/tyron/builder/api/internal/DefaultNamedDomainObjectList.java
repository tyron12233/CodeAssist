package com.tyron.builder.api.internal;

import groovy.lang.Closure;
import com.tyron.builder.api.NamedDomainObjectList;
import com.tyron.builder.api.Namer;
import com.tyron.builder.api.internal.collections.CollectionFilter;
import com.tyron.builder.api.internal.collections.ElementSource;
import com.tyron.builder.api.internal.collections.FilteredList;
import com.tyron.builder.api.internal.collections.IndexedElementSource;
import com.tyron.builder.api.internal.collections.ListElementSource;
import com.tyron.builder.internal.reflect.Instantiator;
import com.tyron.builder.util.Predicates;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.ListIterator;
import java.util.function.Predicate;

public class DefaultNamedDomainObjectList<T> extends DefaultNamedDomainObjectCollection<T> implements NamedDomainObjectList<T> {
    public DefaultNamedDomainObjectList(DefaultNamedDomainObjectList<? super T> objects, CollectionFilter<T> filter, Instantiator instantiator, Namer<? super T> namer) {
        super(objects, filter, instantiator, namer);
    }

    public DefaultNamedDomainObjectList(Class<T> type, Instantiator instantiator, Namer<? super T> namer, CollectionCallbackActionDecorator decorator) {
        super(type, new ListElementSource<T>(), instantiator, namer, decorator);
    }

    @Override
    public void add(int index, T element) {
        assertMutable("add(int, T)");
        assertCanAdd(element);
        getStore().add(index, element);
        didAdd(element);
        getEventRegister().fireObjectAdded(element);
    }

    @Override
    public boolean addAll(int index, Collection<? extends T> c) {
        assertMutable("addAll(int, Collection)");
        boolean changed = false;
        int current = index;
        for (T t : c) {
            if (!hasWithName(getNamer().determineName(t))) {
                getStore().add(current, t);
                didAdd(t);
                getEventRegister().fireObjectAdded(t);
                changed = true;
                current++;
            }
        }
        return changed;
    }

    @Override
    protected IndexedElementSource<T> getStore() {
        return (IndexedElementSource<T>) super.getStore();
    }

    @Override
    public T get(int index) {
        return getStore().get(index);
    }

    @Override
    public T set(int index, T element) {
        assertMutable("set(int, T)");
        assertCanAdd(element);
        T oldElement = getStore().set(index, element);
        if (oldElement != null) {
            didRemove(oldElement);
        }
        getEventRegister().fireObjectRemoved(oldElement);
        didAdd(element);
        getEventRegister().fireObjectAdded(element);
        return oldElement;
    }

    @Override
    public T remove(int index) {
        assertMutable("remove(int)");
        T element = getStore().remove(index);
        if (element != null) {
            didRemove(element);
        }
        getEventRegister().fireObjectRemoved(element);
        return element;
    }

    @Override
    public int indexOf(Object o) {
        return getStore().indexOf(o);
    }

    @Override
    public int lastIndexOf(Object o) {
        return getStore().lastIndexOf(o);
    }

    @Override
    public ListIterator<T> listIterator() {
        return new ListIteratorImpl(getStore().listIterator());
    }

    @Override
    public ListIterator<T> listIterator(int index) {
        return new ListIteratorImpl(getStore().listIterator(index));
    }

    @Override
    public List<T> subList(int fromIndex, int toIndex) {
        return Collections.unmodifiableList(getStore().subList(fromIndex, toIndex));
    }

    @Override
    protected <S extends T> IndexedElementSource<S> filteredStore(CollectionFilter<S> filter, ElementSource<T> elementSource) {
        return new FilteredList<T, S>(elementSource, filter);
    }

    @Override
    public NamedDomainObjectList<T> matching(Closure spec) {
        return matching(Predicates.<T>convertClosureToSpec(spec));
    }

    @Override
    public NamedDomainObjectList<T> matching(Predicate<? super T> spec) {
        return new DefaultNamedDomainObjectList<T>(this, createFilter(spec), getInstantiator(), getNamer());
    }

    @Override
    public <S extends T> NamedDomainObjectList<S> withType(Class<S> type) {
        return new DefaultNamedDomainObjectList<S>(this, createFilter(type), getInstantiator(), getNamer());
    }

    @Override
    public List<T> findAll(Closure cl) {
        return findAll(cl, new ArrayList<T>());
    }

    private class ListIteratorImpl implements ListIterator<T> {
        private final ListIterator<T> iterator;
        private T lastElement;

        public ListIteratorImpl(ListIterator<T> iterator) {
            this.iterator = iterator;
        }

        @Override
        public boolean hasNext() {
            return iterator.hasNext();
        }

        @Override
        public boolean hasPrevious() {
            return iterator.hasPrevious();
        }

        @Override
        public T next() {
            lastElement = iterator.next();
            return lastElement;
        }

        @Override
        public T previous() {
            lastElement = iterator.previous();
            return lastElement;
        }

        @Override
        public int nextIndex() {
            return iterator.nextIndex();
        }

        @Override
        public int previousIndex() {
            return iterator.previousIndex();
        }

        @Override
        public void add(T t) {
            assertMutable("listIterator().add(T)");
            assertCanAdd(t);
            iterator.add(t);
            didAdd(t);
            getEventRegister().fireObjectAdded(t);
        }

        @Override
        public void remove() {
            assertMutable("listIterator().remove()");
            iterator.remove();
            didRemove(lastElement);
            getEventRegister().fireObjectRemoved(lastElement);
            lastElement = null;
        }

        @Override
        public void set(T t) {
            assertMutable("listIterator().set(T)");
            assertCanAdd(t);
            iterator.set(t);
            didRemove(lastElement);
            getEventRegister().fireObjectRemoved(lastElement);
            didAdd(t);
            getEventRegister().fireObjectAdded(t);
            lastElement = null;
        }
    }

}
