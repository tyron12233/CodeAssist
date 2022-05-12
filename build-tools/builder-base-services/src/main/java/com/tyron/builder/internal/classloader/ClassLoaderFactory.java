package com.tyron.builder.internal.classloader;

import com.tyron.builder.internal.classpath.ClassPath;

public interface ClassLoaderFactory {
    /**
     * Returns the ClassLoader that will be used as the parent for all isolated ClassLoaders.
     */
    ClassLoader getIsolatedSystemClassLoader();

    /**
     * Creates a ClassLoader implementation which has only the classes from the specified URIs and the Java API visible.
     */
    ClassLoader createIsolatedClassLoader(String name, ClassPath classPath);

    /**
     * Creates a ClassLoader implementation which has, by default, only the classes from the Java API visible, but which can allow access to selected classes from the given parent ClassLoader.
     *
     * @param parent the parent ClassLoader
     * @param spec the filtering spec for the classloader
     * @return The ClassLoader
     */
    ClassLoader createFilteringClassLoader(ClassLoader parent, FilteringClassLoader.Spec spec);
}
