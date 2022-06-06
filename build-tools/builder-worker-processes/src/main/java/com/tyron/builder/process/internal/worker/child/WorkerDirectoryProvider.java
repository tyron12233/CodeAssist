package com.tyron.builder.process.internal.worker.child;

import java.io.File;

public interface WorkerDirectoryProvider {
    /**
     * Returns a File object representing the working directory for workers.
     */
    File getWorkingDirectory();
}
