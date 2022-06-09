package com.tyron.builder.workers.internal;

import java.io.File;

public abstract class AbstractWorkerRequirement implements WorkerRequirement {
    private final File workerDirectory;

    public AbstractWorkerRequirement(File workerDirectory) {
        this.workerDirectory = workerDirectory;
    }

    @Override
    public File getWorkerDirectory() {
        return workerDirectory;
    }
}
