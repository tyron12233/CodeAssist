package org.jetbrains.kotlin.com.intellij.util.containers;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class ContainerUtil2 {

    /**
     * Processes the list, remove all duplicates and return the list with unique elements.
     * @param list must be sorted (according to the comparator), all elements must be not-null
     */
    public static @NonNull <T> List<? extends T> removeDuplicatesFromSorted(@NonNull List<? extends T> list, @NonNull Comparator<? super T> comparator) {
        T prev = null;
        List<T> result = null;
        for (int i = 0; i < list.size(); i++) {
            T t = list.get(i);
            if (t == null) {
                throw new IllegalArgumentException("get(" + i + ") = null");
            }
            int cmp = prev == null ? -1 : comparator.compare(prev, t);
            if (cmp < 0) {
                if (result != null) result.add(t);
            }
            else if (cmp == 0) {
                if (result == null) {
                    result = new ArrayList<>(list.size());
                    result.addAll(list.subList(0, i));
                }
            }
            else {
                throw new IllegalArgumentException("List must be sorted but get(" + (i - 1) + ")=" + list.get(i - 1) + " > get(" + i + ")=" + t);
            }
            prev = t;
        }
        return result == null ? list : result;
    }
}
