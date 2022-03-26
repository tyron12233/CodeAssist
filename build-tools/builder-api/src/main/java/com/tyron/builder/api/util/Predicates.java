package com.tyron.builder.api.util;

import com.tyron.builder.api.file.FileTreeElement;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.function.Predicate;

public class Predicates {
    public static final Predicate<Object> SATISFIES_ALL = element -> true;

    public static final Predicate<Object> SATISFIES_NONE = element -> false;

    public static <T> Predicate<T> satisfyAll() {
        //noinspection unchecked
        return (Predicate<T>) SATISFIES_ALL;
    }

    public static <T> Predicate<T> satisfyNone() {
        //noinspection unchecked
        return (Predicate<T>) SATISFIES_NONE;
    }

    @SafeVarargs
    public static <T> Predicate<T> intersect(Predicate<? super T>... specs) {
        if (specs.length == 0) {
            return satisfyAll();
        }
        if (specs.length == 1) {
            //noinspection unchecked
            return (Predicate<T>) specs[0];
        }
        return doIntersect(Arrays.<Predicate<? super T>>asList(specs));
    }

    /**
     * Returns a spec that selects the intersection of those items selected by the given specs. Returns a spec that selects everything when no specs provided.
     */
    public static <T> Predicate<T> intersect(Collection<? extends Predicate<? super T>> specs) {
        if (specs.size() == 0) {
            return satisfyAll();
        }
        return doIntersect(specs);
    }

    private static <T> Predicate<T> doIntersect(Collection<? extends Predicate<? super T>> specs) {
        List<Predicate<? super T>> filtered = new ArrayList<>(specs.size());
        for (Predicate<? super T> spec : specs) {
            if (spec == SATISFIES_NONE) {
                return satisfyNone();
            }
            if (spec != SATISFIES_ALL) {
                filtered.add(spec);
            }
        }
        if (filtered.size() == 0) {
            return satisfyAll();
        }
        if (filtered.size() == 1) {
            //noinspection unchecked
            return (Predicate<T>) filtered.get(0);
        }

        @SuppressWarnings("unchecked") Predicate<T> first = (Predicate<T>) filtered.get(0);
        for (int i = 1; i < filtered.size(); i++) {
            Predicate<? super T> predicate = filtered.get(i);
            first = first.and(predicate);
        }
        return first;
    }

    public static <T> Predicate<T> negate(Predicate<T> excludeSpec) {
        return t -> !excludeSpec.test(t);
    }

    /**
     * Returns a spec that selects the union of those items selected by the provided spec. Selects everything when no specs provided.
     */
    public static <T> Predicate<T> union(Collection<? extends Predicate<? super T>> specs) {
        if (specs.size() == 0) {
            return satisfyAll();
        }
        return doUnion(specs);
    }

    private static <T> Predicate<T> doUnion(Collection<? extends Predicate<? super T>> specs) {
        List<Predicate<? super T>> filtered = new ArrayList<Predicate<? super T>>(specs.size());
        for (Predicate<? super T> spec : specs) {
            if (spec == SATISFIES_ALL) {
                return satisfyAll();
            }
            if (spec != SATISFIES_NONE) {
                filtered.add(spec);
            }
        }
        if (filtered.size() == 0) {
            return satisfyNone();
        }
        if (filtered.size() == 1) {
            //noinspection unchecked
            return (Predicate<T>) filtered.get(0);
        }

        @SuppressWarnings("unchecked") Predicate<T> first = (Predicate<T>) filtered.get(0);
        for (int i = 1; i < filtered.size(); i++) {
            Predicate<? super T> predicate = filtered.get(i);
            first = first.or(predicate);
        }
        return first;
    }
}
