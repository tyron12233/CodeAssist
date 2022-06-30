package com.tyron.builder.api.internal.collections;

import java.util.List;
import java.util.ListIterator;

public interface IndexedElementSource<T> extends ElementSource<T> {
    void add(int index, T element);

    T get(int index);

    T set(int index, T element);

    T remove(int index);

    int indexOf(Object o);

    int lastIndexOf(Object o);

    ListIterator<T> listIterator();

    ListIterator<T> listIterator(int index);

    List<? extends T> subList(int fromIndex, int toIndex);
}