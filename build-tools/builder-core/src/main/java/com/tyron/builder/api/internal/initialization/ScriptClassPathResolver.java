package com.tyron.builder.api.internal.initialization;

import com.tyron.builder.api.artifacts.Configuration;
import com.tyron.builder.internal.classpath.ClassPath;

/**
 * Resolves a build script classpath to a set of files in a composite build, ensuring that the
 * required tasks are executed to build artifacts in included builds.
 */
public interface ScriptClassPathResolver {
    ClassPath resolveClassPath(Configuration classpath);
}
