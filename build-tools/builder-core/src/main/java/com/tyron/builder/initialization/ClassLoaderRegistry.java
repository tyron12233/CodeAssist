package com.tyron.builder.initialization;

import com.tyron.builder.internal.classloader.FilteringClassLoader;

public interface ClassLoaderRegistry {
    /**
     * Returns the root class loader shared by all builds. This class loader exposes the Gradle API and APIs for the built-in plugins.
     */
    ClassLoader getGradleApiClassLoader();

    /**
     * Returns the implementation class loader for the Gradle core.
     */
    ClassLoader getRuntimeClassLoader();

    /**
     * Returns the implementation class loader for the built-in plugins.
     */
    ClassLoader getPluginsClassLoader();

    /**
     * Just the Gradle core API, no core plugins.
     */
    ClassLoader getGradleCoreApiClassLoader();

    /**
     * Returns a copy of the filter spec for the Gradle API Classloader.  This is expensive to calculate, so we create it once in
     * the build process and provide it to the worker.
     */
    FilteringClassLoader.Spec getGradleApiFilterSpec();

    /**
     * Returns the extension classloader spec for use in worker processes.  This is expensive to calculate, so we create it once in
     * the build process and provide it to the worker.
     */
    MixInLegacyTypesClassLoader.Spec getGradleWorkerExtensionSpec();
}
