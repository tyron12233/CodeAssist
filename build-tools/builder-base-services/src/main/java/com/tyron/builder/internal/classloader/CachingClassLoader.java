package com.tyron.builder.internal.classloader;

import com.google.common.collect.MapMaker;

import javax.annotation.Nullable;
import java.io.Closeable;
import java.io.IOException;
import java.net.URL;
import java.util.concurrent.ConcurrentMap;

public class CachingClassLoader extends ClassLoader implements ClassLoaderHierarchy, Closeable {
    private static final Object MISSING = new Object();
    private final ConcurrentMap<String, Object> loadedClasses = new MapMaker().weakValues().makeMap();
    private final ConcurrentMap<String, Object> resources = new MapMaker().makeMap();
    private final ClassLoader parent;

    static {
        try {
            ClassLoader.registerAsParallelCapable();
        } catch (NoSuchMethodError ignore) {
            // Not supported on Java 6
        }
    }

    public CachingClassLoader(ClassLoader parent) {
        super(parent);
        this.parent = parent;
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        Object cachedValue = loadedClasses.get(name);
        if (cachedValue instanceof Class) {
            return (Class<?>) cachedValue;
        } else if (cachedValue == MISSING) {
            throw new ClassNotFoundException(name);
        }
        Class<?> result;
        try {
            result = super.loadClass(name, resolve);
        } catch (ClassNotFoundException e) {
            loadedClasses.putIfAbsent(name, MISSING);
            throw e;
        }
        loadedClasses.putIfAbsent(name, result);
        return result;
    }

    @Nullable
    @Override
    public URL getResource(String name) {
        Object cachedValue = resources.get(name);
        if (cachedValue == MISSING) {
            return null;
        }
        if (cachedValue != null) {
            return (URL) cachedValue;
        }
        URL result = super.getResource(name);
        resources.putIfAbsent(name, result != null ? result : MISSING);
        return result;
    }

    @Override
    public void visit(ClassLoaderVisitor visitor) {
        visitor.visitSpec(new Spec());
        visitor.visitParent(getParent());
    }

    @Override
    public void close() throws IOException {
        loadedClasses.clear();
        resources.clear();
    }

    @Override
    public String toString() {
        return CachingClassLoader.class.getSimpleName() + "(" + getParent() + ")";
    }

    public static class Spec extends ClassLoaderSpec {
        @Override
        public boolean equals(Object obj) {
            return obj != null && obj.getClass().equals(Spec.class);
        }

        @Override
        public int hashCode() {
            return getClass().getName().hashCode();
        }
    }

    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof CachingClassLoader)) {
            return false;
        }

        CachingClassLoader that = (CachingClassLoader) o;
        return parent.equals(that.parent);
    }

    public int hashCode() {
        return parent.hashCode();
    }
}
