package org.gradle.api.internal;

import org.gradle.internal.classpath.ClassPath;

public interface ClassPathRegistry {
    ClassPath getClassPath(String name);
}
