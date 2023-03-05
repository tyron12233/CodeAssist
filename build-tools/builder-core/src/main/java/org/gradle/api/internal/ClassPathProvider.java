package org.gradle.api.internal;

import org.gradle.internal.classpath.ClassPath;

import javax.annotation.Nullable;

public interface ClassPathProvider {
    /**
     * Returns the files for the given classpath, if known. Returns null for unknown classpath.
     */
    @Nullable
    ClassPath findClassPath(String name);
}
