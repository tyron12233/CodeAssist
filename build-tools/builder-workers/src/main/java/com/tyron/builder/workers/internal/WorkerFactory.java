package com.tyron.builder.workers.internal;

public interface WorkerFactory {
    BuildOperationAwareWorker getWorker(WorkerRequirement workerRequirement);

    IsolationMode getIsolationMode();
}
