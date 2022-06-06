package com.tyron.builder.workers.internal;

import com.tyron.builder.internal.classloader.ClassLoaderSpec;

public interface ClassLoaderStructure {
    ClassLoaderSpec getSpec();

    ClassLoaderStructure getParent();
}
