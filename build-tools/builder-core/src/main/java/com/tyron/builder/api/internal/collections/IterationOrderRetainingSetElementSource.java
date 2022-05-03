package com.tyron.builder.api.internal.collections;

import com.google.common.base.Objects;
import com.google.common.collect.Iterators;
import com.tyron.builder.api.internal.provider.CollectionProviderInternal;
import com.tyron.builder.api.internal.provider.ProviderInternal;

import java.util.Iterator;
import java.util.List;
import java.util.function.Predicate;

public class IterationOrderRetainingSetElementSource<T> extends AbstractIterationOrderRetainingElementSource<T> {
    private final Predicate<ValuePointer<T>> noDuplicates =
            pointer -> !pointer.getElement().isDuplicate(pointer.getIndex());

    @Override
    public Iterator<T> iterator() {
        realizePending();
        return new RealizedElementCollectionIterator(getInserted(), noDuplicates);
    }

    @Override
    public Iterator<T> iteratorNoFlush() {
        return new RealizedElementCollectionIterator(getInserted(), noDuplicates);
    }

    @Override
    public boolean add(T element) {
        modCount++;
        if (!Iterators.contains(iteratorNoFlush(), element)) {
            getInserted().add(new Element<T>(element));
            return true;
        } else {
            return false;
        }
    }

    @Override
    public boolean addRealized(T value) {
        markDuplicates(value);
        return true;
    }

    @Override
    protected void clearCachedElement(Element<T> element) {
        boolean wasRealized = element.isRealized();
        super.clearCachedElement(element);
        if (wasRealized) {
            for (T value : element.getValues()) {
                markDuplicates(value);
            }
        }
    }

    private void markDuplicates(T value) {
        boolean seen = false;
        for (Element<T> element : getInserted()) {
            if (element.isRealized()) {
                List<T> collected = element.getValues();
                for (int index = 0; index < collected.size(); index++) {
                    if (Objects.equal(collected.get(index), value)) {
                        if (seen) {
                            element.setDuplicate(index);
                        } else {
                            seen = true;
                        }
                    }
                }
            }
        }
    }

    @Override
    public boolean addPending(ProviderInternal<? extends T> provider) {
        modCount++;
        Element<T> element = cachingElement(provider);
        if (!getInserted().contains(element)) {
            getInserted().add(element);
            return true;
        } else {
            return false;
        }
    }

    @Override
    public boolean addPendingCollection(CollectionProviderInternal<T, ? extends Iterable<T>> provider) {
        modCount++;
        Element<T> element = cachingElement(provider);
        if (!getInserted().contains(element)) {
            getInserted().add(element);
            return true;
        } else {
            return false;
        }
    }
}
