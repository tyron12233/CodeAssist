package com.tyron.builder.api.internal.hash;

import com.google.common.hash.HashCode;

import org.jetbrains.annotations.Nullable;

/**
 * Provides a combined hash for a hierarchy of classloaders.
 */
public interface ClassLoaderHierarchyHasher {
    /**
     * Returns a hash for the given classloader hierarchy, or {@code null}
     * if the hierarchy contains any classloaders that are not known to Gradle.
     */
    @Nullable
    HashCode getClassLoaderHash(ClassLoader classLoader);
}