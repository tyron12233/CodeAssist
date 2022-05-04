package com.tyron.builder.api.internal.initialization;

import com.google.common.hash.HashCode;
import com.tyron.builder.api.internal.initialization.loadercache.ClassLoaderCache;
import com.tyron.builder.initialization.ClassLoaderScopeId;
import com.tyron.builder.initialization.ClassLoaderScopeRegistryListener;
import com.tyron.builder.internal.classpath.ClassPath;

import javax.annotation.Nullable;
import java.util.function.Function;

/**
 * Provides common {@link #getPath} and {@link #createChild} behaviour for {@link ClassLoaderScope} implementations.
 */
public abstract class AbstractClassLoaderScope implements ClassLoaderScope {

    protected final ClassLoaderScopeIdentifier id;
    protected final ClassLoaderCache classLoaderCache;
    protected final ClassLoaderScopeRegistryListener listener;

    protected AbstractClassLoaderScope(ClassLoaderScopeIdentifier id, ClassLoaderCache classLoaderCache, ClassLoaderScopeRegistryListener listener) {
        this.id = id;
        this.classLoaderCache = classLoaderCache;
        this.listener = listener;
    }

    /**
     * Unique identifier of this scope in the hierarchy.
     */
    public ClassLoaderScopeId getId() {
        return id;
    }

    /**
     * A string representing the path of this {@link ClassLoaderScope} in the {@link ClassLoaderScope} graph.
     */
    public String getPath() {
        return id.getPath();
    }

    @Override
    public ClassLoaderScope local(ClassPath classPath) {
        return immutable();
    }

    @Override
    public ClassLoaderScope export(ClassPath classPath) {
        return immutable();
    }

    @Override
    public ClassLoaderScope export(ClassLoader classLoader) {
        return immutable();
    }

    private ClassLoaderScope immutable() {
        throw new UnsupportedOperationException(String.format("Class loader scope %s is immutable", id));
    }

    @Override
    public ClassLoaderScope createChild(String name) {
            return new DefaultClassLoaderScope(id.child(name), this, classLoaderCache, listener);
    }

    @Override
    public ClassLoaderScope createLockedChild(String name, ClassPath localClasspath, @Nullable HashCode classpathImplementationHash, Function<ClassLoader, ClassLoader> localClassLoaderFactory) {
        return new ImmutableClassLoaderScope(id.child(name), this, localClasspath, classpathImplementationHash, localClassLoaderFactory, classLoaderCache, listener);
    }
}
