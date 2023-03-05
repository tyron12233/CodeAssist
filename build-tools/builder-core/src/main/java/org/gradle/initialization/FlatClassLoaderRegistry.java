package org.gradle.initialization;

import org.gradle.internal.classloader.FilteringClassLoader;

public class FlatClassLoaderRegistry implements ClassLoaderRegistry {

    private final ClassLoader classLoader;

    public FlatClassLoaderRegistry(ClassLoader classLoader) {
        this.classLoader = classLoader;
    }

    @Override
    public ClassLoader getGradleApiClassLoader() {
        return classLoader;
    }

    @Override
    public ClassLoader getRuntimeClassLoader() {
        return classLoader;
    }

    @Override
    public ClassLoader getPluginsClassLoader() {
        return classLoader;
    }

    @Override
    public ClassLoader getGradleCoreApiClassLoader() {
        return classLoader;
    }

    @Override
    public FilteringClassLoader.Spec getGradleApiFilterSpec() {
        throw new UnsupportedOperationException();
    }

    @Override
    public MixInLegacyTypesClassLoader.Spec getGradleWorkerExtensionSpec() {
        throw new UnsupportedOperationException();
    }
}