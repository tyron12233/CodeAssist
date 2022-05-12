package com.tyron.builder.api.internal.initialization.loadercache;

import com.google.common.hash.HashCode;
import com.tyron.builder.internal.classloader.FilteringClassLoader;
import com.tyron.builder.internal.classpath.ClassPath;

import javax.annotation.Nullable;
import java.util.function.Function;

//@UsedByScanPlugin("test-retry")
public interface ClassLoaderCache {

    /**
     * Returns an existing classloader from the cache, or creates it if it cannot be found.
     *
     * @param id the ID of the classloader.
     * @param classPath the classpath to use to create the classloader.
     * @param parent the parent of the classloader.
     * @param filterSpec the filtering to use on the classpath.
     * @return the classloader.
     */
    ClassLoader get(ClassLoaderId id, ClassPath classPath, @Nullable ClassLoader parent, @Nullable FilteringClassLoader.Spec filterSpec);

    /**
     * Returns an existing classloader from the cache, or creates it if it cannot be found.
     *
     * @param id the ID of the classloader.
     * @param classPath the classpath to use to create the classloader.
     * @param parent the parent of the classloader.
     * @param filterSpec the filtering to use on the classpath.
     * @param implementationHash a hash that represents the contents of the classpath. Can be {@code null}, in which case the hash is calculated from the provided classpath
     * @return the classloader.
     */
    ClassLoader get(ClassLoaderId id, ClassPath classPath, @Nullable ClassLoader parent, @Nullable FilteringClassLoader.Spec filterSpec, @Nullable HashCode implementationHash);

    /**
     * Adds or replaces a classloader. This should be called to register specialized classloaders that belong to the hierarchy, so they can be cleaned up as required.
     *
     * @param id the ID of the classloader.
     * @return the classloader.
     */
    ClassLoader createIfAbsent(ClassLoaderId id, ClassPath classPath, @Nullable ClassLoader parent, Function<ClassLoader, ClassLoader> factoryFunction, @Nullable HashCode implementationHash);

    /**
     * Discards the given classloader.
     */
    void remove(ClassLoaderId id);
}
