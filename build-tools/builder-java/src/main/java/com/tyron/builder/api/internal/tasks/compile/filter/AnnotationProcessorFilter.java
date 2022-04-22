package com.tyron.builder.api.internal.tasks.compile.filter;

import com.tyron.builder.internal.classloader.FilteringClassLoader;

public class AnnotationProcessorFilter {
    public static FilteringClassLoader getFilteredClassLoader(ClassLoader parent) {
        return new FilteringClassLoader(parent, getExtraAllowedPackages());
    }

    /**
     * Many popular annotation processors like lombok need access to compiler internals
     * to do their magic, e.g. to inspect or even change method bodies. This is not valid
     * according to the annotation processing spec, but forbidding it would upset a lot of
     * our users.
     */
    private static FilteringClassLoader.Spec getExtraAllowedPackages() {
        FilteringClassLoader.Spec spec = new FilteringClassLoader.Spec();
        spec.allowPackage("com.sun.tools.javac");
        spec.allowPackage("com.sun.source");
        return spec;
    }
}

