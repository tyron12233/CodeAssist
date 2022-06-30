package com.tyron.builder.internal.instantiation.generator;

import java.util.Comparator;

/**
 * Sorts GeneratedConstructors based on the number of parameters.
 *
 * When two constructors have the same number of parameters, we settle on a stable sort by looking at the names of the
 * types of the parameters.
 *
 */
public class ConstructorComparator implements Comparator<ClassGenerator.GeneratedConstructor<?>> {
    @Override
    public int compare(ClassGenerator.GeneratedConstructor<?> o1, ClassGenerator.GeneratedConstructor<?> o2) {
        int parameterSort = Integer.compare(o1.getParameterTypes().length, o2.getParameterTypes().length);
        if (parameterSort == 0) {
            // Both constructors have the same number of parameters
            // Create a stable sort based on the names of all the parameters
            long lhs = 0;
            for (Class<?> paramType : o1.getParameterTypes()) {
                lhs += paramType.getCanonicalName().hashCode();
            }
            long rhs=0;
            for (Class<?> paramType : o2.getParameterTypes()) {
                rhs += paramType.getCanonicalName().hashCode();
            }
            return Long.compare(lhs, rhs);
        }
        return parameterSort;
    }
}
