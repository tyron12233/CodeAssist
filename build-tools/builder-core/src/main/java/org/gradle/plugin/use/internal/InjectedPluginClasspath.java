package org.gradle.plugin.use.internal;

import org.gradle.internal.classpath.ClassPath;

public class InjectedPluginClasspath {

    private final ClassPath classPath;

    public InjectedPluginClasspath(ClassPath classPath) {
        this.classPath = classPath;
    }

    public ClassPath getClasspath() {
        return classPath;
    }

}
