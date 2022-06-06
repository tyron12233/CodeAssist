package com.tyron.builder.workers.internal;

import com.google.common.collect.Sets;
import com.tyron.builder.initialization.ClassLoaderRegistry;
import com.tyron.builder.initialization.MixInLegacyTypesClassLoader;
import com.tyron.builder.internal.classloader.ClasspathUtil;
import com.tyron.builder.internal.classloader.FilteringClassLoader;
import com.tyron.builder.internal.classloader.VisitableURLClassLoader;
import com.tyron.builder.internal.classpath.DefaultClassPath;

import java.io.File;
import java.net.URL;
import java.util.ArrayList;
import java.util.Set;

public class ClassLoaderStructureProvider {
    private final ClassLoaderRegistry classLoaderRegistry;

    public ClassLoaderStructureProvider(final ClassLoaderRegistry classLoaderRegistry) {
        this.classLoaderRegistry = classLoaderRegistry;
    }

    public ClassLoaderStructure getWorkerProcessClassLoaderStructure(final Iterable<File> additionalClasspath, Class<?>... classes) {
        MixInLegacyTypesClassLoader.Spec workerExtensionSpec = classLoaderRegistry.getGradleWorkerExtensionSpec();
        FilteringClassLoader.Spec gradleApiFilter = classLoaderRegistry.getGradleApiFilterSpec();
        VisitableURLClassLoader.Spec userSpec = getUserSpec("worker-loader", additionalClasspath, classes);

        // Add the Gradle API filter between the user classloader and the worker infrastructure classloader
        return new HierarchicalClassLoaderStructure(workerExtensionSpec)
                .withChild(gradleApiFilter)
                .withChild(userSpec);
    }

    public ClassLoaderStructure getInProcessClassLoaderStructure(final Iterable<File> additionalClasspath, Class<?>... classes) {
        FilteringClassLoader.Spec gradleApiFilter = classLoaderRegistry.getGradleApiFilterSpec();
        VisitableURLClassLoader.Spec userSpec = getUserSpec("worker-loader", additionalClasspath, classes);
        // Add the Gradle API filter between the user classloader and the worker infrastructure classloader
        return new HierarchicalClassLoaderStructure(gradleApiFilter)
                .withChild(userSpec);
    }

    /**
     * Returns a spec representing the combined "user" classloader for the given classes and additional classpath.  The user classloader assumes it is used as a child of a classloader with the Gradle API.
     */
    public VisitableURLClassLoader.Spec getUserSpec(String name, Iterable<File> additionalClasspath, Class<?>... classes) {
        Set<URL> classpath = Sets.newLinkedHashSet();
        classpath.addAll(DefaultClassPath.of(additionalClasspath).getAsURLs());

        Set<ClassLoader> uniqueClassloaders = Sets.newHashSet();
        for (Class<?> clazz : classes) {
            ClassLoader classLoader = clazz.getClassLoader();
            // System types come from the system classloader and their classloader is null.
            if (classLoader != null) {
                uniqueClassloaders.add(classLoader);
            }
        }
        for (ClassLoader classLoader : uniqueClassloaders) {
            ClasspathUtil.collectClasspathUntil(classLoader, classLoaderRegistry.getGradleApiClassLoader(), classpath);
        }
        return new VisitableURLClassLoader.Spec(name, new ArrayList<>(classpath));
    }
}
