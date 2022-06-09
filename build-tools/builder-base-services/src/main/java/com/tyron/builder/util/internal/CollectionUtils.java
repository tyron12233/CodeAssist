package com.tyron.builder.util.internal;

import static com.tyron.builder.internal.Cast.cast;
import static com.tyron.builder.internal.Cast.uncheckedNonnullCast;

import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.Lists;
import com.tyron.builder.api.Transformer;
import com.tyron.builder.api.specs.Spec;
import com.tyron.builder.internal.resources.ResourceLock;

import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.Type;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

public class CollectionUtils {

    /**
     * Recursively unpacks all the given things into a flat list.
     *
     * Nulls are not removed, they are left intact.
     *
     * @param things The things to flatten
     * @return A flattened list of the given things
     */
    public static List<?> flattenCollections(Object... things) {
        return flattenCollections(Object.class, things);
    }

    /**
     * Recursively unpacks all the given things into a flat list, ensuring they are of a certain type.
     *
     * Nulls are not removed, they are left intact.
     *
     * If a non null object cannot be cast to the target type, a ClassCastException will be thrown.
     *
     * @param things The things to flatten
     * @param <T> The target type in the flattened list
     * @return A flattened list of the given things
     */
    public static <T> List<T> flattenCollections(Class<T> type, Object... things) {
        if (things == null) {
            return Collections.singletonList(null);
        } else if (things.length == 0) {
            return Collections.emptyList();
        } else if (things.length == 1) {
            Object thing = things[0];

            if (thing == null) {
                return Collections.singletonList(null);
            }

            // Casts to Class below are to workaround Eclipse compiler bug
            // See: https://github.com/gradle/gradle/pull/200

            if (thing.getClass().isArray()) {
                Object[] thingArray = (Object[]) thing;
                List<T> list = new ArrayList<T>(thingArray.length);
                for (Object thingThing : thingArray) {
                    list.addAll(flattenCollections(type, thingThing));
                }
                return list;
            }

            if (thing instanceof Collection) {
                Collection<?> collection = (Collection<?>) thing;
                List<T> list = new ArrayList<T>();
                for (Object element : collection) {
                    list.addAll(flattenCollections(type, element));
                }
                return list;
            }

            return Collections.singletonList(cast(type, thing));
        } else {
            List<T> list = new ArrayList<T>();
            for (Object thing : things) {
                list.addAll(flattenCollections(type, thing));
            }
            return list;
        }
    }

    @Nullable
    public static <T> T findFirst(Iterable<? extends T> source, Predicate<? super T> filter) {
        for (T item : source) {
            if (filter.test(item)) {
                return item;
            }
        }

        return null;
    }

    @Nullable
    public static <T> T findFirst(T[] source, Predicate<? super T> filter) {
        for (T thing : source) {
            if (filter.test(thing)) {
                return thing;
            }
        }

        return null;
    }

    public static List<String> toStringList(Iterable<?> iterable) {
        return collect(iterable, new LinkedList<>(),
                (Transformer<String, Object>) Object::toString);
    }

    /**
     * Returns a sorted copy of the provided collection of things. Uses the provided comparator to sort.
     */
    public static <T> List<T> sort(Iterable<? extends T> things, Comparator<? super T> comparator) {
        List<T> copy = toMutableList(things);
        Collections.sort(copy, comparator);
        return copy;
    }

    /**
     * Returns the single element in the collection or throws.
     */
    public static <T> T single(Iterable<? extends T> source) {
        Iterator<? extends T> iterator = source.iterator();
        if (!iterator.hasNext()) {
            throw new NoSuchElementException("Expecting collection with single element, got none.");
        }
        T element = iterator.next();
        if (iterator.hasNext()) {
            throw new IllegalArgumentException("Expecting collection with single element, got multiple.");
        }
        return element;
    }

    /**
     * Returns a sorted copy of the provided collection of things. Uses the natural ordering of the things.
     */
    public static <T extends Comparable<T>> List<T> sort(Iterable<T> things) {
        List<T> copy = toMutableList(things);
        Collections.sort(copy);
        return copy;
    }

    public static <R, I> List<R> collect(I[] list, Transformer<? extends R, ? super I> transformer) {
        return collect(Arrays.asList(list), transformer);
    }

    public static <R, I> Set<R> collect(Set<? extends I> set, Transformer<? extends R, ? super I> transformer) {
        return collect(set, new HashSet<R>(set.size()), transformer);
    }

    public static <R, I> List<R> collect(Iterable<? extends I> source, Transformer<? extends R, ? super I> transformer) {
        if (source instanceof Collection<?>) {
            Collection<? extends I> collection = uncheckedNonnullCast(source);
            return collect(source, new ArrayList<R>(collection.size()), transformer);
        } else {
            return collect(source, new LinkedList<R>(), transformer);
        }
    }

    public static <R, I, C extends Collection<R>> C collect(Iterable<? extends I> source, C destination, Transformer<? extends R, ? super I> transformer) {
        for (I item : source) {
            destination.add(transformer.transform(item));
        }
        return destination;
    }

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

    private static <T> List<T> toMutableList(Iterable<? extends T> things) {
        if (things == null) {
            return new ArrayList<T>(0);
        }
        List<T> list = new ArrayList<T>();
        for (T thing : things) {
            list.add(thing);
        }
        return list;
    }


    public static <T> List<T> intersection(Collection<? extends Collection<T>> availableValuesByDescriptor) {
        List<T> result = new ArrayList<T>();
        Iterator<? extends Collection<T>> iterator = availableValuesByDescriptor.iterator();
        if (iterator.hasNext()) {
            Collection<T> firstSet = iterator.next();
            result.addAll(firstSet);
            while (iterator.hasNext()) {
                Collection<T> next = iterator.next();
                result.retainAll(next);
            }
        }
        return result;

    }

    public static <T> List<T> toList(T[] things) {
        if (things == null || things.length == 0) {
            return new ArrayList<T>(0);
        }

        List<T> list = new ArrayList<T>(things.length);
        Collections.addAll(list, things);
        return list;
    }

    public static <T> Set<T> toSet(Iterable<? extends T> things) {
        if (things == null) {
            return new HashSet<T>(0);
        }
        if (things instanceof Set) {
            @SuppressWarnings("unchecked") Set<T> castThings = (Set<T>) things;
            return castThings;
        }

        Set<T> set = new LinkedHashSet<T>();
        for (T thing : things) {
            set.add(thing);
        }
        return set;
    }


    /**
     * Utility for adding an iterable to a collection.
     *
     * @param t1 The collection to add to
     * @param t2 The iterable to add each item of to the collection
     * @param <T> The element type of t1
     * @return t1
     */
    public static <T, C extends Collection<? super T>> C addAll(C t1, Iterable<? extends T> t2) {
        for (T t : t2) {
            t1.add(t);
        }
        return t1;
    }

    /**
     * Utility for adding an array to a collection.
     *
     * @param t1 The collection to add to
     * @param t2 The iterable to add each item of to the collection
     * @param <T> The element type of t1
     * @return t1
     */
    public static <T, C extends Collection<? super T>> C addAll(C t1, T... t2) {
        Collections.addAll(t1, t2);
        return t1;
    }

    public static Iterable<String> stringize(Collection<?> compilerArgs) {
        return compilerArgs.stream().map(Objects::toString).collect(Collectors.toList());
    }

    public static <K, V> Map<K, Collection<V>> groupBy(Iterable<? extends V> iterable, Transformer<? extends K, V> grouper) {
        ImmutableListMultimap.Builder<K, V> builder = ImmutableListMultimap.builder();

        for (V element : iterable) {
            K key = grouper.transform(element);
            builder.put(key, element);
        }

        return builder.build().asMap();
    }

    public static <R, I> R[] collectArray(I[] list, Class<R> newType, Transformer<? extends R, ? super I> transformer) {
        @SuppressWarnings("unchecked") R[] destination = (R[]) Array.newInstance(newType, list.length);
        return collectArray(list, destination, transformer);
    }

    public static <R, I> R[] collectArray(I[] list, R[] destination, Transformer<? extends R, ? super I> transformer) {
        assert list.length <= destination.length;
        for (int i = 0; i < list.length; ++i) {
            destination[i] = transformer.transform(list[i]);
        }
        return destination;
    }

    public static <T> boolean any(Iterable<? extends T> source, Spec<? super T> filter) {
        return findFirst(source, filter) != null;
    }

    public static <T> boolean any(T[] source, Spec<? super T> filter) {
        return findFirst(source, filter) != null;
    }

    @Nullable
    public static <T> T findFirst(Iterable<? extends T> source, Spec<? super T> filter) {
        for (T item : source) {
            if (filter.isSatisfiedBy(item)) {
                return item;
            }
        }

        return null;
    }

    @Nullable
    public static <T> T findFirst(T[] source, Spec<? super T> filter) {
        for (T thing : source) {
            if (filter.isSatisfiedBy(thing)) {
                return thing;
            }
        }

        return null;
    }

    public static <T> boolean every(Iterable<? extends T> locks,
                                    Predicate<T> resourceLockSpec) {
        for (T lock : locks) {
            if (!resourceLockSpec.test(lock)) {
                return false;
            }
        }
        return true;
    }
}
