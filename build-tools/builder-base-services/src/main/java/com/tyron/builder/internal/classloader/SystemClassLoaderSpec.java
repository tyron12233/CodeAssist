package com.tyron.builder.internal.classloader;

public class SystemClassLoaderSpec extends ClassLoaderSpec {
    public static final ClassLoaderSpec INSTANCE = new SystemClassLoaderSpec();

    private SystemClassLoaderSpec() {
    }

    @Override
    public boolean equals(Object obj) {
        return obj != null && obj.getClass().equals(getClass());
    }

    @Override
    public String toString() {
        return "{system-class-loader}";
    }

    @Override
    public int hashCode() {
        return 121;
    }
}