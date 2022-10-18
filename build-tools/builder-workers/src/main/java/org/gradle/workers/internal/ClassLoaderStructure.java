package org.gradle.workers.internal;

import org.gradle.internal.classloader.ClassLoaderSpec;

public interface ClassLoaderStructure {
    ClassLoaderSpec getSpec();

    ClassLoaderStructure getParent();
}
