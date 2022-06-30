package com.tyron.builder.util.internal;

import com.tyron.builder.api.DomainObjectSet;
import com.tyron.builder.api.internal.CollectionCallbackActionDecorator;
import com.tyron.builder.api.internal.DefaultDomainObjectSet;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * Common methods to wrap objects in generic collections.
 */
public class WrapUtil {
    /**
     * Wraps the given items in a mutable unordered set.
     */
    @SafeVarargs
    @SuppressWarnings("varargs")
    public static <T> Set<T> toSet(T... items) {
        Set<T> coll = new HashSet<T>();
        Collections.addAll(coll, items);
        return coll;
    }

    /**
     * Wraps the given items in a mutable domain object set.
     */
    @SafeVarargs
    @SuppressWarnings("varargs")
    public static <T> DomainObjectSet<T> toDomainObjectSet(Class<T> type, T... items) {
        DefaultDomainObjectSet<T> set = new DefaultDomainObjectSet<T>(type, CollectionCallbackActionDecorator.NOOP);
        set.addAll(Arrays.asList(items));
        return set;
    }

    /**
     * Wraps the given items in a mutable ordered set.
     */
    @SafeVarargs
    @SuppressWarnings("varargs")
    public static <T> Set<T> toLinkedSet(T... items) {
        Set<T> coll = new LinkedHashSet<T>();
        Collections.addAll(coll, items);
        return coll;
    }

    /**
     * Wraps the given items in a mutable sorted set.
     */
    @SafeVarargs
    @SuppressWarnings("varargs")
    public static <T> SortedSet<T> toSortedSet(T... items) {
        SortedSet<T> coll = new TreeSet<T>();
        Collections.addAll(coll, items);
        return coll;
    }

    /**
     * Wraps the given items in a mutable sorted set using the given comparator.
     */
    @SafeVarargs
    @SuppressWarnings("varargs")
    public static <T> SortedSet<T> toSortedSet(Comparator<T> comp, T... items) {
        SortedSet<T> coll = new TreeSet<T>(comp);
        Collections.addAll(coll, items);
        return coll;
    }

    /**
     * Wraps the given items in a mutable list.
     */
    @SafeVarargs
    @SuppressWarnings("varargs")
    public static <T> List<T> toList(T... items) {
        ArrayList<T> coll = new ArrayList<T>();
        Collections.addAll(coll, items);
        return coll;
    }

    /**
     * Wraps the given items in a mutable list.
     */
    public static <T> List<T> toList(Iterable<? extends T> items) {
        ArrayList<T> coll = new ArrayList<T>();
        for (T item : items) {
            coll.add(item);
        }
        return coll;
    }

    /**
     * Wraps the given key and value in a mutable unordered map.
     */
    public static <K, V> Map<K, V> toMap(K key, V value) {
        Map<K, V> map = new HashMap<K, V>();
        map.put(key, value);
        return map;
    }

    @SafeVarargs
    @SuppressWarnings("varargs")
    public static <T> T[] toArray(T... items) {
        return items;
    }

}
