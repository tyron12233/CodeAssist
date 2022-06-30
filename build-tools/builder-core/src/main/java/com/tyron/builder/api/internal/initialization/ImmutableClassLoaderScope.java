package com.tyron.builder.api.internal.initialization;

import com.google.common.hash.HashCode;
import com.tyron.builder.api.internal.initialization.loadercache.ClassLoaderCache;
import com.tyron.builder.api.internal.initialization.loadercache.ClassLoaderId;
import com.tyron.builder.initialization.ClassLoaderScopeRegistryListener;
import com.tyron.builder.internal.classpath.ClassPath;

import javax.annotation.Nullable;
import java.util.function.Function;

/**
 * A simplified scope that provides only a single local classpath and no exports, and that cannot be mutated.
 */
public class ImmutableClassLoaderScope extends AbstractClassLoaderScope {
    private final ClassLoaderScope parent;
    private final ClassPath classPath;
    @Nullable
    private final HashCode classpathImplementationHash;
    private final ClassLoader localClassLoader;

    public ImmutableClassLoaderScope(ClassLoaderScopeIdentifier id, ClassLoaderScope parent, ClassPath classPath, @Nullable HashCode classpathImplementationHash,
                                     @Nullable Function<ClassLoader, ClassLoader> localClassLoaderFactory, ClassLoaderCache classLoaderCache, ClassLoaderScopeRegistryListener listener) {
        super(id, classLoaderCache, listener);
        this.parent = parent;
        this.classPath = classPath;
        this.classpathImplementationHash = classpathImplementationHash;
        listener.childScopeCreated(parent.getId(), id);
        ClassLoaderId classLoaderId = id.localId();
        if (localClassLoaderFactory != null) {
            localClassLoader = classLoaderCache.createIfAbsent(classLoaderId, classPath, parent.getExportClassLoader(), localClassLoaderFactory, classpathImplementationHash);
        } else {
            localClassLoader = classLoaderCache.get(classLoaderId, classPath, parent.getExportClassLoader(), null, classpathImplementationHash);
        }
        listener.classloaderCreated(id, classLoaderId, localClassLoader, classPath, classpathImplementationHash);
    }

    @Override
    public ClassLoaderScope getParent() {
        return parent;
    }

    @Override
    public ClassLoader getExportClassLoader() {
        return parent.getExportClassLoader();
    }

    @Override
    public ClassLoader getLocalClassLoader() {
        return localClassLoader;
    }

    @Override
    public boolean defines(Class<?> clazz) {
        return localClassLoader.equals(clazz.getClassLoader());
    }

    @Override
    public void onReuse() {
        parent.onReuse();
        listener.childScopeCreated(parent.getId(), id);
        listener.classloaderCreated(id, id.localId(), localClassLoader, classPath, classpathImplementationHash);
    }

    @Override
    public ClassLoaderScope lock() {
        return this;
    }

    @Override
    public boolean isLocked() {
        return true;
    }
}
