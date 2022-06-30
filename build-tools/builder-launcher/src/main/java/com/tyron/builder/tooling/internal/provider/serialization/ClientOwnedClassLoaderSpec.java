package com.tyron.builder.tooling.internal.provider.serialization;

import com.tyron.builder.internal.classloader.ClassLoaderSpec;

import java.net.URL;
import java.util.List;

public class ClientOwnedClassLoaderSpec extends ClassLoaderSpec {
    private final List<URL> classpath;

    public ClientOwnedClassLoaderSpec(List<URL> classpath) {
        this.classpath = classpath;
    }

    public List<URL> getClasspath() {
        return classpath;
    }

    @Override
    public String toString() {
        return "{client-owned-class-loader classpath: " + classpath + "}";
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == this) {
            return true;
        }
        if (obj == null || obj.getClass() != getClass()) {
            return false;
        }
        ClientOwnedClassLoaderSpec other = (ClientOwnedClassLoaderSpec) obj;
        return classpath.equals(other.classpath);
    }

    @Override
    public int hashCode() {
        return classpath.hashCode();
    }
}
