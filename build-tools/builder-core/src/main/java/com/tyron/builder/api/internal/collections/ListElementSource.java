package com.tyron.builder.api.internal.collections;

import com.google.common.collect.Lists;
import com.tyron.builder.api.internal.provider.CollectionProviderInternal;
import com.tyron.builder.api.internal.provider.ProviderInternal;

import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.NoSuchElementException;
import java.util.function.Predicate;

public class ListElementSource<T> extends AbstractIterationOrderRetainingElementSource<T> implements IndexedElementSource<T> {

    private final Predicate<ValuePointer<T>> alwaysAccept = new Predicate<ValuePointer<T>>() {
        @Override
        public boolean test(ValuePointer<T> pointer) {
            return true;
        }
    };

    @Override
    public Iterator<T> iterator() {
        realizePending();
        return listIterator();
    }

    @Override
    public Iterator<T> iteratorNoFlush() {
        return listIterator();
    }

    @Override
    public ListIterator<T> listIterator() {
        return new RealizedElementListIterator(getInserted(), alwaysAccept);
    }

    @Override
    public ListIterator<T> listIterator(int index) {
        return Lists.newArrayList(listIterator()).listIterator(index);
    }

    @Override
    public List<? extends T> subList(int fromIndex, int toIndex) {
        return Lists.newArrayList(listIterator()).subList(fromIndex, toIndex);
    }

    private List<T> asList() {
        return Lists.newArrayList(listIterator());
    }

    @Override
    public T get(int index) {
        return asList().get(index);
    }

    @Override
    public int indexOf(Object o) {
        return asList().indexOf(o);
    }

    @Override
    public int lastIndexOf(Object o) {
        return asList().lastIndexOf(o);
    }

    @Override
    public boolean add(T element) {
        modCount++;
        return getInserted().add(new Element<T>(element));
    }

    @Override
    public boolean addRealized(T value) {
        return true;
    }

    @Override
    public boolean addPending(ProviderInternal<? extends T> provider) {
        modCount++;
        return getInserted().add(cachingElement(provider));
    }

    @Override
    public boolean addPendingCollection(CollectionProviderInternal<T, ? extends Iterable<T>> provider) {
        modCount++;
        return getInserted().add(cachingElement(provider));
    }

    private ListIterator<T> iteratorAt(int index) {
        ListIterator<T> iterator = listIterator();
        while(iterator.previousIndex() < index && iterator.hasNext()) {
            iterator.next();
        }
        return iterator;
    }

    @Override
    public void add(int index, T element) {
        modCount++;
        ListIterator<T> iterator = iteratorAt(index - 1);
        if (iterator.nextIndex() == index) {
            iterator.add(element);
        } else {
            throw new IndexOutOfBoundsException();
        }
    }

    @Override
    public T set(int index, T element) {
        modCount++;
        ListIterator<T> iterator = iteratorAt(index - 1);
        if (!iterator.hasNext()) {
            throw new IndexOutOfBoundsException();
        }
        T previous = iterator.next();
        iterator.set(element);
        return previous;
    }

    @Override
    public T remove(int index) {
        modCount++;
        ListIterator<T> iterator = iteratorAt(index - 1);
        if (!iterator.hasNext()) {
            throw new IndexOutOfBoundsException();
        }
        T previous = iterator.next();
        iterator.remove();
        return previous;
    }

    private class RealizedElementListIterator extends RealizedElementCollectionIterator implements ListIterator<T> {
        T previous;
        int listNextIndex = 0;
        int listPreviousIndex = -1;

        RealizedElementListIterator(List<Element<T>> backingList, Predicate<ValuePointer<T>> acceptanceSpec) {
            super(backingList, acceptanceSpec);
        }

        @Override
        public boolean hasPrevious() {
            return previous != null;
        }

        private void updatePrevious() {
            int i = previousIndex;
            while (i >= 0) {
                Element<T> candidate = backingList.get(i);
                if (candidate.isRealized()) {
                    List<T> collected = candidate.getValues();
                    if (previousSubIndex == -1) {
                        previousSubIndex = collected.size();
                    }
                    int j = previousSubIndex - 1;
                    while (j >= 0) {
                        T value = collected.get(j);
                        if (acceptanceSpec.test(new ValuePointer<T>(candidate, j))) {
                            previousIndex = i;
                            previousSubIndex = j;
                            previous = value;
                            return;
                        }
                        j--;
                    }
                    previousSubIndex = -1;
                }
                i--;
            }
            previousIndex = -1;
            previous = null;
        }

        @Override
        public T next() {
            T value = super.next();
            previous = backingList.get(previousIndex).getValues().get(previousSubIndex);
            listNextIndex++;
            listPreviousIndex++;
            return value;
        }

        @Override
        public T previous() {
            checkForComodification();
            if (previous == null) {
                throw new NoSuchElementException();
            }
            nextIndex = previousIndex;
            nextSubIndex = previousSubIndex;
            next = previous;
            updatePrevious();
            listNextIndex--;
            listPreviousIndex--;
            return next;
        }

        @Override
        public int nextIndex() {
            return listNextIndex;
        }

        @Override
        public int previousIndex() {
            return listPreviousIndex;
        }

        @Override
        public void set(T t) {
            if (previousIndex < 0) {
                throw new IllegalStateException();
            }
            checkForComodification();
            backingList.set(previousIndex, new Element<T>(t));
        }

        @Override
        public void add(T t) {
            checkForComodification();
            Element<T> element = new Element<T>(t);
            backingList.add(nextIndex, element);
            nextIndex++;
            previous = element.getValues().get(0);
            previousIndex = nextIndex;
            previousSubIndex = 0;
        }

        @Override
        public void remove() {
            super.remove();
            previous = null;
        }
    }
}