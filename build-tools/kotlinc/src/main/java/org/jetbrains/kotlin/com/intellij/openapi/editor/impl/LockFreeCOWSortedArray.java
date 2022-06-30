package org.jetbrains.kotlin.com.intellij.openapi.editor.impl;

import androidx.annotation.NonNull;

import org.jetbrains.kotlin.com.intellij.util.ArrayFactory;
import org.jetbrains.kotlin.com.intellij.util.ArrayUtil;

import java.util.Comparator;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Maintains an atomic immutable array of listeners of type {@code T} in sorted order according to {@link #comparator}
 * N.B. internal array is exposed for faster iterating listeners in to- and reverse order, so care should be taken for not mutating it by clients
 */
class LockFreeCOWSortedArray<T> extends AtomicReference<T[]> {
    @NonNull private final Comparator<? super T> comparator;
    private final @NonNull ArrayFactory<? extends T> arrayFactory;

    LockFreeCOWSortedArray(@NonNull Comparator<? super T> comparator, @NonNull ArrayFactory<? extends T> arrayFactory) {
        this.comparator = comparator;
        this.arrayFactory = arrayFactory;
        set(arrayFactory.create(0));
    }

    // returns true if changed
    void add(@NonNull T element) {
        while (true) {
            T[] oldArray = get();
            int i = insertionIndex(oldArray, element);
            T[] newArray = ArrayUtil.insert(oldArray, i, element);
            if (compareAndSet(oldArray, newArray)) break;
        }
    }

    boolean remove(@NonNull T listener) {
        while (true) {
            T[] oldArray = get();
            T[] newArray = ArrayUtil.remove(oldArray, listener, arrayFactory);
            //noinspection ArrayEquality
            if (oldArray == newArray) return false;
            if (compareAndSet(oldArray, newArray)) break;
        }
        return true;
    }

    private int insertionIndex(T[] elements, @NonNull T e) {
        for (int i=0; i<elements.length; i++) {
            T element = elements[i];
            if (comparator.compare(e, element) < 0) {
                return i;
            }
        }
        return elements.length;
    }

    T[] getArray() {
        return get();
    }
}