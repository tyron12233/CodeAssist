package org.gradle.tooling.internal.provider.serialization;

import org.gradle.internal.classloader.ClassLoaderSpec;

import java.util.List;

/**
 * Used to create ClassLoaders used to serialize objects between the tooling api provider and daemon.
 *
 * <p>Implementations are not required to be thread-safe.</p>
 */
public interface PayloadClassLoaderFactory {
    ClassLoader getClassLoaderFor(ClassLoaderSpec spec, List<? extends ClassLoader> parents);
}
