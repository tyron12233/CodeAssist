package org.gradle.initialization;

import org.gradle.api.internal.initialization.ClassLoaderScope;
import org.gradle.api.internal.initialization.RootClassLoaderScope;
import org.gradle.api.internal.initialization.loadercache.ClassLoaderCache;

public class DefaultClassLoaderScopeRegistry implements ClassLoaderScopeRegistry {

    private final ClassLoaderScope coreAndPluginsScope;
    private final ClassLoaderScope coreScope;

    public DefaultClassLoaderScopeRegistry(ClassLoaderRegistry loaderRegistry, ClassLoaderCache classLoaderCache, ClassLoaderScopeRegistryListener listener) {
        this.coreScope = new RootClassLoaderScope("core", loaderRegistry.getRuntimeClassLoader(), loaderRegistry.getGradleCoreApiClassLoader(), classLoaderCache, listener);
        this.coreAndPluginsScope = new RootClassLoaderScope("coreAndPlugins", loaderRegistry.getPluginsClassLoader(), loaderRegistry.getGradleApiClassLoader(), classLoaderCache, listener);
    }

    @Override
    public ClassLoaderScope getCoreAndPluginsScope() {
        return coreAndPluginsScope;
    }

    @Override
    public ClassLoaderScope getCoreScope() {
        return coreScope;
    }

}
