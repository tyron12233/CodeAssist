package com.tyron.builder.workers.internal;

import java.io.File;

public class ForkedWorkerRequirement extends IsolatedClassLoaderWorkerRequirement {
    private final DaemonForkOptions forkOptions;

    public ForkedWorkerRequirement(File workerDirectory, DaemonForkOptions forkOptions) {
        super(workerDirectory, forkOptions.getClassLoaderStructure());
        this.forkOptions = forkOptions;
    }

    public DaemonForkOptions getForkOptions() {
        return forkOptions;
    }
}
