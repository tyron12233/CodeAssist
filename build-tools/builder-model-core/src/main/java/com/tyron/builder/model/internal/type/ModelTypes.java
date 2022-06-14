package com.tyron.builder.model.internal.type;

import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Ordering;
import com.google.common.collect.Sets;
import groovy.lang.GroovyObject;
import com.tyron.builder.model.ModelMap;
import com.tyron.builder.model.ModelSet;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;
import java.util.Set;

public abstract class ModelTypes {

    public static <I> ModelType<ModelMap<I>> modelMap(Class<I> type) {
        return modelMap(ModelType.of(type));
    }

    public static <I> ModelType<ModelMap<I>> modelMap(ModelType<I> type) {
        return new ModelType.Builder<ModelMap<I>>() {
        }.where(
            new ModelType.Parameter<I>() {
            }, type
        ).build();
    }

    public static <I> ModelType<ModelSet<I>> modelSet(ModelType<I> type) {
        return new ModelType.Builder<ModelSet<I>>() {
        }.where(
            new ModelType.Parameter<I>() {
            }, type
        ).build();
    }

    public static <I> ModelType<List<I>> list(ModelType<I> type) {
        return new ModelType.Builder<List<I>>() {
        }.where(
            new ModelType.Parameter<I>() {
            }, type
        ).build();
    }

    public static <I> ModelType<Set<I>> set(ModelType<I> type) {
        return new ModelType.Builder<Set<I>>() {
        }.where(
            new ModelType.Parameter<I>() {
            }, type
        ).build();
    }

    public static <T> Ordering<ModelType<? extends T>> displayOrder() {
        return new Ordering<ModelType<? extends T>>() {
            @Override
            public int compare(ModelType<? extends T> left, ModelType<? extends T> right) {
                return left.getDisplayName().compareTo(right.getDisplayName());
            }
        };
    }

    /**
     * Returns the sorted, unique display names of the given types.
     */
    public static Iterable<String> getDisplayNames(Iterable<? extends ModelType<?>> types) {
        ImmutableSortedSet.Builder<String> builder = ImmutableSortedSet.naturalOrder();
        for (ModelType<?> type : types) {
            builder.add(type.getDisplayName());
        }
        return builder.build();
    }

    /**
     * Collect all types that make up the type hierarchy of the given types.
     */
    public static Set<ModelType<?>> collectHierarchy(Iterable<? extends ModelType<?>> types) {
        Queue<ModelType<?>> queue = new ArrayDeque<ModelType<?>>(Iterables.size(types) * 2);
        Iterables.addAll(queue, types);
        Set<ModelType<?>> seenTypes = Sets.newLinkedHashSet();
        ModelType<?> type;
        while ((type = queue.poll()) != null) {
            // Do not process Object's or GroovyObject's methods
            Class<?> rawClass = type.getRawClass();
            if (rawClass.equals(Object.class) || rawClass.equals(GroovyObject.class)) {
                continue;
            }
            // Do not reprocess
            if (!seenTypes.add(type)) {
                continue;
            }

            Class<?> superclass = rawClass.getSuperclass();
            if (superclass != null) {
                ModelType<?> superType = ModelType.of(superclass);
                if (!seenTypes.contains(superType)) {
                    queue.add(superType);
                }
            }
            for (Class<?> iface : rawClass.getInterfaces()) {
                ModelType<?> ifaceType = ModelType.of(iface);
                if (!seenTypes.contains(ifaceType)) {
                    queue.add(ifaceType);
                }
            }
        }

        return seenTypes;
    }
}
