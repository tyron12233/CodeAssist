package com.tyron.builder.api.internal.initialization.loadercache;

import com.tyron.builder.api.Describable;

/**
 * Opaque identifier of the classloader. Needed for correct behavior of classloader invalidation.
 */
public interface ClassLoaderId extends Describable {
    boolean equals(Object o);
    int hashCode();
}
