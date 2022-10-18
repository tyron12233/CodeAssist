package org.gradle.internal.work;

import org.gradle.concurrent.ParallelismConfiguration;
import org.gradle.internal.concurrent.ExecutorFactory;

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
