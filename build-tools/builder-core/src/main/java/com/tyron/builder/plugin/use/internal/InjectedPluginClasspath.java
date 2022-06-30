package com.tyron.builder.plugin.use.internal;

import com.tyron.builder.internal.classpath.ClassPath;

public class InjectedPluginClasspath {

    private final ClassPath classPath;

    public InjectedPluginClasspath(ClassPath classPath) {
        this.classPath = classPath;
    }

    public ClassPath getClasspath() {
        return classPath;
    }

}
