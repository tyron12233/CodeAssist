package org.gradle.api.internal.initialization;

import org.gradle.api.artifacts.Configuration;
import org.gradle.internal.classpath.ClassPath;

/**
 * Resolves a build script classpath to a set of files in a composite build, ensuring that the
 * required tasks are executed to build artifacts in included builds.
 */
public interface ScriptClassPathResolver {
    ClassPath resolveClassPath(Configuration classpath);
}
