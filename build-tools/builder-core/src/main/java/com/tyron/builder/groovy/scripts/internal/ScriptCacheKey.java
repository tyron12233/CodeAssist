package com.tyron.builder.groovy.scripts.internal;

import java.lang.ref.WeakReference;

public class ScriptCacheKey {
    private final String className;
    private final WeakReference<ClassLoader> classLoader;
    private final String dslId;

    ScriptCacheKey(String className, ClassLoader classLoader, String dslId) {
        this.className = className;
        this.classLoader = new WeakReference<ClassLoader>(classLoader);
        this.dslId = dslId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        ScriptCacheKey key = (ScriptCacheKey) o;

        return classLoader.get() != null && key.classLoader.get() != null
            && classLoader.get().equals(key.classLoader.get())
            && className.equals(key.className)
            && dslId.equals(key.dslId);
    }

    @Override
    public int hashCode() {
        ClassLoader loader = this.classLoader.get();
        int result = className.hashCode();
        result = 31 * result + (loader != null ? loader.hashCode() : 1);
        result = 31 * result + dslId.hashCode();
        return result;
    }
}
