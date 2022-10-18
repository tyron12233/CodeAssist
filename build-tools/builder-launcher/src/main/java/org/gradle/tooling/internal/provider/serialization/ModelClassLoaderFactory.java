package org.gradle.tooling.internal.provider.serialization;

import org.gradle.TaskExecutionRequest;
import org.gradle.internal.classloader.CachingClassLoader;
import org.gradle.internal.classloader.ClassLoaderSpec;
import org.gradle.internal.classloader.FilteringClassLoader;
import org.gradle.internal.classloader.MultiParentClassLoader;
import org.gradle.internal.classloader.SystemClassLoaderSpec;
import org.gradle.internal.classloader.VisitableURLClassLoader;

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
