package com.tyron.builder.workers.internal;

import java.io.File;

public class FixedClassLoaderWorkerRequirement extends AbstractWorkerRequirement {
    private final ClassLoader contextClassLoader;

    public FixedClassLoaderWorkerRequirement(File workerDirectory, ClassLoader contextClassLoader) {
        super(workerDirectory);
        this.contextClassLoader = contextClassLoader;
    }

    public ClassLoader getContextClassLoader() {
        return contextClassLoader;
    }
}
