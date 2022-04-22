package com.tyron.builder.internal.classloader;

import com.google.common.hash.HashCode;
import com.tyron.builder.internal.classpath.ClassPath;

import org.jetbrains.annotations.Nullable;

/**
 * A {@link ClassLoaderFactory} that also stores the hash of each created classloader which is later retrievable via {@link #getClassLoaderClasspathHash(ClassLoader)}.
 */
public interface HashingClassLoaderFactory extends ClassLoaderFactory {
    /**
     * Creates a {@link ClassLoader} with the given parent and classpath. Use the given hash
     * code, or calculate it from the given classpath when hash code is {@code null}.
     */
    ClassLoader createChildClassLoader(String name, ClassLoader parent, ClassPath classPath, @Nullable HashCode implementationHash);

    /**
     * Returns the hash associated with the classloader's classpath, or {@code null} if the classloader is unknown to Gradle.
     * The hash only represents the classloader's classpath only, regardless of whether or not there are any parent classloaders.
     */
    @Nullable
    HashCode getClassLoaderClasspathHash(ClassLoader classLoader);
}