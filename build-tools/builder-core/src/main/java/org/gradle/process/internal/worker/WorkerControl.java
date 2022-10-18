package org.gradle.process.internal.worker;

import org.gradle.process.ExecResult;

public interface WorkerControl {
    /**
     * Starts the worker process, blocking until successfully started.
     */
    WorkerProcess start();

    /**
     * Requests that the worker complete all work and stop. Blocks until the worker process has stopped.
     */
    ExecResult stop();
}
