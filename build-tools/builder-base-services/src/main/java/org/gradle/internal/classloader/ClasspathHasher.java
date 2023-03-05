package org.gradle.internal.classloader;

import com.google.common.hash.HashCode;
import org.gradle.internal.classpath.ClassPath;

public interface ClasspathHasher {
    /**
     * Returns a strong hash for a given classpath.
     */
    HashCode hash(ClassPath classpath);
}
