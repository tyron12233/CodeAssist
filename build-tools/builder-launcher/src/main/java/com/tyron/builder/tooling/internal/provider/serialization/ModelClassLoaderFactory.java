package com.tyron.builder.tooling.internal.provider.serialization;

import com.tyron.builder.TaskExecutionRequest;
import com.tyron.builder.internal.classloader.CachingClassLoader;
import com.tyron.builder.internal.classloader.ClassLoaderSpec;
import com.tyron.builder.internal.classloader.FilteringClassLoader;
import com.tyron.builder.internal.classloader.MultiParentClassLoader;
import com.tyron.builder.internal.classloader.SystemClassLoaderSpec;
import com.tyron.builder.internal.classloader.VisitableURLClassLoader;

import java.util.List;

public class ModelClassLoaderFactory implements PayloadClassLoaderFactory {
    private final ClassLoader rootClassLoader;

    public ModelClassLoaderFactory() {
        ClassLoader parent = getClass().getClassLoader();
        FilteringClassLoader.Spec filterSpec = new FilteringClassLoader.Spec();
        filterSpec.allowPackage("org.gradle.tooling.internal.protocol");
        filterSpec.allowClass(TaskExecutionRequest.class);
        rootClassLoader = new FilteringClassLoader(parent, filterSpec);
    }

    @Override
    public ClassLoader getClassLoaderFor(ClassLoaderSpec spec, List<? extends ClassLoader> parents) {
        if (spec instanceof SystemClassLoaderSpec) {
            return rootClassLoader;
        }
        if (spec instanceof MultiParentClassLoader.Spec) {
            return new MultiParentClassLoader(parents);
        }
        if (parents.size() != 1) {
            throw new IllegalArgumentException("Expected a single parent.");
        }
        ClassLoader parent = parents.get(0);
        if (spec instanceof VisitableURLClassLoader.Spec) {
            VisitableURLClassLoader.Spec clSpec = (VisitableURLClassLoader.Spec) spec;
            return new VisitableURLClassLoader(clSpec.getName(), parent, clSpec.getClasspath());
        }
        if (spec instanceof CachingClassLoader.Spec) {
            return new CachingClassLoader(parent);
        }
        if (spec instanceof FilteringClassLoader.Spec) {
            FilteringClassLoader.Spec clSpec = (FilteringClassLoader.Spec) spec;
            return new FilteringClassLoader(parent, clSpec);
        }
        throw new IllegalArgumentException(String.format("Don't know how to create a ClassLoader from spec %s", spec));
    }
}
