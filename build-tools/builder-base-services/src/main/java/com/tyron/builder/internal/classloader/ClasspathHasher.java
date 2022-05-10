package com.tyron.builder.internal.classloader;

import com.google.common.hash.HashCode;
import com.tyron.builder.internal.classpath.ClassPath;

public interface ClasspathHasher {
    /**
     * Returns a strong hash for a given classpath.
     */
    HashCode hash(ClassPath classpath);
}
