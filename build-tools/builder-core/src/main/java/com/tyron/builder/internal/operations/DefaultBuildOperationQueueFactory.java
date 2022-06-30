package com.tyron.builder.internal.operations;

import com.tyron.builder.internal.concurrent.ManagedExecutor;
import com.tyron.builder.internal.work.WorkerLeaseService;

public class DefaultBuildOperationQueueFactory implements BuildOperationQueueFactory {
    private final WorkerLeaseService workerLeaseService;

    public DefaultBuildOperationQueueFactory(WorkerLeaseService workerLeaseService) {
        this.workerLeaseService = workerLeaseService;
    }

    @Override
    public <T extends BuildOperation> BuildOperationQueue<T>
    create(ManagedExecutor executor, boolean allowAccessToProjectState, BuildOperationQueue.QueueWorker<T> worker) {
        // Assert that the current thread is a worker
        workerLeaseService.getCurrentWorkerLease();
        return new DefaultBuildOperationQueue<>(allowAccessToProjectState, workerLeaseService, executor, worker);
    }
}