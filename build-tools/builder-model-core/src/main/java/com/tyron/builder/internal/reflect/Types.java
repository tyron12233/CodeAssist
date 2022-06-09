package com.tyron.builder.internal.reflect;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Sets;
import com.tyron.builder.internal.Cast;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Queue;
import java.util.Set;

public class Types {
    private static final Collection<Class<?>> OBJECT_TYPE = ImmutableList.<Class<?>>of(Object.class);

    /**
     * Visits all types in a type hierarchy in breadth-first order, super-classes first and then implemented interfaces.
     *
     * @param clazz the type of whose type hierarchy to visit.
     * @param visitor the visitor to call for each type in the hierarchy.
     */
    public static <T> void walkTypeHierarchy(Class<T> clazz, TypeVisitor<? extends T> visitor) {
        walkTypeHierarchy(clazz, OBJECT_TYPE, visitor);
    }

    /**
     * Visits all types in a type hierarchy in breadth-first order, super-classes first and then implemented interfaces.
     *
     * @param clazz the type of whose type hierarchy to visit.
     * @param excludedTypes the types not to walk when encountered in the hierarchy.
     * @param visitor the visitor to call for each type in the hierarchy.
     */
    public static <T> void walkTypeHierarchy(Class<T> clazz, Collection<Class<?>> excludedTypes, TypeVisitor<? extends T> visitor) {
        Set<Class<?>> seenInterfaces = Sets.newHashSet();
        Queue<Class<? super T>> queue = new ArrayDeque<Class<? super T>>();
        queue.add(clazz);
        Class<? super T> type;
        while ((type = queue.poll()) != null) {
            if (excludedTypes.contains(type)) {
                continue;
            }

            visitor.visitType(type);

            Class<? super T> superclass = type.getSuperclass();
            if (superclass != null) {
                queue.add(superclass);
            }
            for (Class<?> iface : type.getInterfaces()) {
                if (seenInterfaces.add(iface)) {
                    queue.add(Cast.<Class<? super T>>uncheckedCast(iface));
                }
            }
        }
    }

    @FunctionalInterface
    public interface TypeVisitor<T> {
        void visitType(Class<? super T> type);
    }
}
