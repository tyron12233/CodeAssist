package com.tyron.builder.api.internal.initialization;

import com.tyron.builder.api.internal.initialization.loadercache.ClassLoaderCache;
import com.tyron.builder.initialization.ClassLoaderScopeRegistryListener;
import com.tyron.builder.internal.classloader.CachingClassLoader;

public class RootClassLoaderScope extends AbstractClassLoaderScope {

    private final ClassLoader localClassLoader;
    private final CachingClassLoader cachingLocalClassLoader;
    private final ClassLoader exportClassLoader;
    private final CachingClassLoader cachingExportClassLoader;

    public RootClassLoaderScope(String name, ClassLoader localClassLoader, ClassLoader exportClassLoader, ClassLoaderCache classLoaderCache, ClassLoaderScopeRegistryListener listener) {
        super(new ClassLoaderScopeIdentifier(null, name), classLoaderCache, listener);
        this.localClassLoader = localClassLoader;
        this.cachingLocalClassLoader = new CachingClassLoader(localClassLoader);
        this.exportClassLoader = exportClassLoader;
        this.cachingExportClassLoader = new CachingClassLoader(exportClassLoader);
    }

    @Override
    public ClassLoader getLocalClassLoader() {
        return cachingLocalClassLoader;
    }

    @Override
    public ClassLoader getExportClassLoader() {
        return cachingExportClassLoader;
    }

    @Override
    public ClassLoaderScope getParent() {
        return this; // should this be null?
    }

    @Override
    public boolean defines(Class<?> clazz) {
        return localClassLoader.equals(clazz.getClassLoader()) || exportClassLoader.equals(clazz.getClassLoader());
    }

    @Override
    public ClassLoaderScope lock() {
        return this;
    }

    @Override
    public boolean isLocked() {
        return true;
    }

    @Override
    public void onReuse() {
        // Nothing to do
    }
}
