package com.tyron.builder.api.internal.collections;

import com.tyron.builder.api.internal.WithEstimatedSize;
import com.tyron.builder.api.internal.WithMutationGuard;

import java.util.Collection;
import java.util.Iterator;

public interface ElementSource<T> extends Iterable<T>, WithEstimatedSize, PendingSource<T>, WithMutationGuard {
    /**
     * Iterates over and realizes each of the elements of this source.
     */
    @Override
    Iterator<T> iterator();

    /**
     * Iterates over only the realized elements (without flushing any pending elements)
     */
    Iterator<T> iteratorNoFlush();

    /**
     * Returns false if this source is not empty or it is not fast to determine this.
     */
    boolean constantTimeIsEmpty();

    @Override
    int estimatedSize();

    boolean contains(Object element);

    boolean containsAll(Collection<?> elements);

    @Override
    boolean isEmpty();

    boolean add(T element);

    boolean addRealized(T element);

    @Override
    void clear();

    boolean remove(Object o);

    @Override
    int size();
}