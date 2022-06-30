package com.tyron.builder.internal.work;

import com.tyron.builder.concurrent.ParallelismConfiguration;
import com.tyron.builder.internal.concurrent.ExecutorFactory;

public class DefaultConditionalExecutionQueueFactory implements ConditionalExecutionQueueFactory {
    private final ParallelismConfiguration parallelismConfiguration;
    private final ExecutorFactory executorFactory;
    private final WorkerLeaseService workerLeaseService;

    public DefaultConditionalExecutionQueueFactory(
        ParallelismConfiguration parallelismConfiguration,
        ExecutorFactory executorFactory,
        WorkerLeaseService workerLeaseService
    ) {
        this.parallelismConfiguration = parallelismConfiguration;
        this.executorFactory = executorFactory;
        this.workerLeaseService = workerLeaseService;
    }

    @Override
    public <T> ConditionalExecutionQueue<T> create(String displayName, Class<T> resultClass) {
        return new DefaultConditionalExecutionQueue<T>(displayName, parallelismConfiguration.getMaxWorkerCount(), executorFactory, workerLeaseService);
    }
}
