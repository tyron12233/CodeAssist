package com.tyron.builder.api.util;

import com.google.common.collect.Lists;
import com.tyron.builder.api.internal.file.FileCollectionInternal;

import java.io.File;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

public class CollectionUtils {
    /**
     * Creates a string with {@code toString()} of each object with the given separator.
     *
     * <pre>
     * expect:
     * join(",", new Object[]{"a"}) == "a"
     * join(",", new Object[]{"a", "b", "c"}) == "a,b,c"
     * join(",", new Object[]{}) == ""
     * </pre>
     *
     * The {@code separator} must not be null and {@code objects} must not be null.
     *
     * @param separator The string by which to join each string representation
     * @param objects The objects to join the string representations of
     * @return The joined string
     */
    public static String join(String separator, Object[] objects) {
        return join(separator, objects == null ? null : Arrays.asList(objects));
    }

    /**
     * Creates a string with {@code toString()} of each object with the given separator.
     *
     * <pre>
     * expect:
     * join(",", ["a"]) == "a"
     * join(",", ["a", "b", "c"]) == "a,b,c"
     * join(",", []) == ""
     * </pre>
     *
     * The {@code separator} must not be null and {@code objects} must not be null.
     *
     * @param separator The string by which to join each string representation
     * @param objects The objects to join the string representations of
     * @return The joined string
     */
    public static String join(String separator, Iterable<?> objects) {
        if (separator == null) {
            throw new NullPointerException("The 'separator' cannot be null");
        }
        if (objects == null) {
            throw new NullPointerException("The 'objects' cannot be null");
        }

        StringBuilder string = new StringBuilder();
        Iterator<?> iterator = objects.iterator();
        if (iterator.hasNext()) {
            string.append(iterator.next().toString());
            while (iterator.hasNext()) {
                string.append(separator);
                string.append(iterator.next().toString());
            }
        }
        return string.toString();
    }



    public static <T> Set<T> filter(Set<? extends T> set, Predicate<? super T> filter) {
        return filter(set, new LinkedHashSet<T>(), filter);
    }

    public static <T> List<T> filter(List<? extends T> list, Predicate<? super T> filter) {
        return filter(list, Lists.<T>newArrayListWithCapacity(list.size()), filter);
    }

    public static <T> List<T> filter(T[] array, Predicate<? super T> filter) {
        return filter(Arrays.asList(array), Lists.<T>newArrayListWithCapacity(array.length), filter);
    }

    public static <T, C extends Collection<T>> C filter(Iterable<? extends T> source, C destination, Predicate<? super T> filter) {
        for (T item : source) {
            if (filter.test(item)) {
                destination.add(item);
            }
        }
        return destination;
    }

    public static <K, V> Map<K, V> filter(Map<K, V> map, Predicate<Map.Entry<K, V>> filter) {
        return filter(map, new HashMap<K, V>(), filter);
    }

    public static <K, V> Map<K, V> filter(Map<K, V> map, Map<K, V> destination, Predicate<Map.Entry<K, V>> filter) {
        for (Map.Entry<K, V> entry : map.entrySet()) {
            if (filter.test(entry)) {
                destination.put(entry.getKey(), entry.getValue());
            }
        }
        return destination;
    }
}
