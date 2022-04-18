package com.tyron.builder.api.internal;


import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.TreeSet;

public interface WithEstimatedSize {
    /**
     * Returns an estimate of the size of this collection or iterator. The idea is that this information
     * can be used internally to make better default dimensions for temporary objects. Therefore, it is
     * intended to return a value which is greater or equal to the real size (pessimistic). The typical use
     * case is creating a hash set or hash map of a collection without knowing the number of elements it
     * will contain. With this method we can properly size it and avoid resizes. The reason we use an
     * estimate size instead of the real size is that sometimes the real size is too expensive to compute.
     * Typically in a filtered collection you would have to actually do the filtering before knowing the size,
     * so killing all possible optimizations. Instead, it should return the size of the collection it wraps,
     * so that we have an estimate of the number of elements it may contain. The closer to reality is the better,
     * of course.
     *
     * @return the estimate size of the object
     */
    int estimatedSize();

    class Estimates {
        public static <T> int estimateSizeOf(Collection<T> collection) {
            if (isKnownToHaveConstantTimeSizeMethod(collection)) {
                return collection.size();
            }
            if (collection instanceof WithEstimatedSize) {
                return ((WithEstimatedSize) collection).estimatedSize();
            }
            return 10; // we don't know if the underlying collection can return a size in constant time
        }

        public static <T> boolean isKnownToHaveConstantTimeSizeMethod(Collection<T> collection) {
            Class<? extends Collection> clazz = collection.getClass();
            if (clazz == HashSet.class || clazz == ArrayList.class || clazz == LinkedList.class || clazz == TreeSet.class) {
                return true;
            }
            return false;
        }
    }
}