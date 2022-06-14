package com.tyron.builder.workers.internal;

import java.io.File;

public class IsolatedClassLoaderWorkerRequirement extends AbstractWorkerRequirement {
    private final ClassLoaderStructure classLoaderStructure;

    public IsolatedClassLoaderWorkerRequirement(File workerDirectory, ClassLoaderStructure classLoaderStructure) {
        super(workerDirectory);
        this.classLoaderStructure = classLoaderStructure;
    }

    public ClassLoaderStructure getClassLoaderStructure() {
        return classLoaderStructure;
    }
}
