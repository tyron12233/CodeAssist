package org.gradle.workers.internal;

public interface WorkerFactory {
    BuildOperationAwareWorker getWorker(WorkerRequirement workerRequirement);

    IsolationMode getIsolationMode();
}
