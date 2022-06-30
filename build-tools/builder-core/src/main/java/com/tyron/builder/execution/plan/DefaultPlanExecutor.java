package com.tyron.builder.execution.plan;

import com.tyron.builder.api.Action;
import com.tyron.builder.api.NonNullApi;
import com.tyron.builder.concurrent.ParallelismConfiguration;
import com.tyron.builder.initialization.BuildCancellationToken;
import com.tyron.builder.internal.MutableBoolean;
import com.tyron.builder.internal.MutableReference;
import com.tyron.builder.internal.concurrent.ExecutorFactory;
import com.tyron.builder.internal.concurrent.ManagedExecutor;
import com.tyron.builder.internal.resources.ResourceLockCoordinationService;
import com.tyron.builder.internal.time.Time;
import com.tyron.builder.internal.time.TimeFormatting;
import com.tyron.builder.internal.time.Timer;
import com.tyron.builder.internal.work.WorkerLeaseRegistry.WorkerLease;
import com.tyron.builder.internal.work.WorkerLeaseService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.util.Collection;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;

import static com.tyron.builder.internal.resources.ResourceLockState.Disposition.FINISHED;
import static com.tyron.builder.internal.resources.ResourceLockState.Disposition.RETRY;

@NonNullApi
public class DefaultPlanExecutor implements PlanExecutor {
    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultPlanExecutor.class);
    private final int executorCount;
    private final ExecutorFactory executorFactory;
    private final WorkerLeaseService workerLeaseService;
    private final BuildCancellationToken cancellationToken;
    private final ResourceLockCoordinationService coordinationService;

    public DefaultPlanExecutor(ParallelismConfiguration parallelismConfiguration, ExecutorFactory executorFactory, WorkerLeaseService workerLeaseService, BuildCancellationToken cancellationToken, ResourceLockCoordinationService coordinationService) {
        this.executorFactory = executorFactory;
        this.cancellationToken = cancellationToken;
        this.coordinationService = coordinationService;
        int numberOfParallelExecutors = parallelismConfiguration.getMaxWorkerCount();
        if (numberOfParallelExecutors < 1) {
            throw new IllegalArgumentException("Not a valid number of parallel executors: " + numberOfParallelExecutors);
        }

        this.executorCount = numberOfParallelExecutors;
        this.workerLeaseService = workerLeaseService;
    }

    @Override
    public void process(ExecutionPlan executionPlan, Collection<? super Throwable> failures, Action<Node> nodeExecutor) {
        ManagedExecutor executor = executorFactory.create("Execution worker for '" + executionPlan.getDisplayName() + "'");
        try {
            WorkerLease currentWorkerLease = workerLeaseService.getCurrentWorkerLease();
            startAdditionalWorkers(executionPlan, nodeExecutor, executor);
            new ExecutorWorker(executionPlan, nodeExecutor, currentWorkerLease, cancellationToken, coordinationService, workerLeaseService).run();
            awaitCompletion(executionPlan, failures);
        } finally {
            executor.stop();
        }
    }

    /**
     * Blocks until all nodes in the plan have been processed. This method will only return when every node in the plan has either completed, failed or been skipped.
     */
    private void awaitCompletion(final ExecutionPlan executionPlan, final Collection<? super Throwable> failures) {
        coordinationService.withStateLock(resourceLockState -> {
            if (executionPlan.allNodesComplete()) {
                executionPlan.collectFailures(failures);
                return FINISHED;
            } else {
                return RETRY;
            }
        });
    }

    private void startAdditionalWorkers(ExecutionPlan executionPlan, Action<? super Node> nodeExecutor, Executor executor) {
        LOGGER.debug("Using {} parallel executor threads", executorCount);

        for (int i = 1; i < executorCount; i++) {
            executor.execute(new ExecutorWorker(executionPlan, nodeExecutor, null, cancellationToken, coordinationService, workerLeaseService));
        }
    }

    private static class ExecutorWorker implements Runnable {
        private final ExecutionPlan executionPlan;
        private final Action<? super Node> nodeExecutor;
        private WorkerLease workerLease;
        private final BuildCancellationToken cancellationToken;
        private final ResourceLockCoordinationService coordinationService;
        private final WorkerLeaseService workerLeaseService;

        private ExecutorWorker(
                ExecutionPlan executionPlan,
                Action<? super Node> nodeExecutor,
                @Nullable WorkerLease workerLease,
                BuildCancellationToken cancellationToken,
                ResourceLockCoordinationService coordinationService,
                WorkerLeaseService workerLeaseService
        ) {
            this.executionPlan = executionPlan;
            this.nodeExecutor = nodeExecutor;
            this.workerLease = workerLease;
            this.cancellationToken = cancellationToken;
            this.coordinationService = coordinationService;
            this.workerLeaseService = workerLeaseService;
        }

        @Override
        public void run() {
            final AtomicLong busy = new AtomicLong(0);
            Timer totalTimer = Time.startTimer();
            final Timer executionTimer = Time.startTimer();

            boolean releaseLeaseOnCompletion;
            if (workerLease == null) {
                workerLease = workerLeaseService.getWorkerLease();
                releaseLeaseOnCompletion = true;
            } else {
                releaseLeaseOnCompletion = false;
            }

            while (true) {
                boolean nodesRemaining = executeNextNode(workerLease, work -> {
                    LOGGER.info("{} ({}) started.", work, Thread.currentThread());
                    executionTimer.reset();
                    nodeExecutor.execute(work);
                    long duration = executionTimer.getElapsedMillis();
                    busy.addAndGet(duration);
                    if (LOGGER.isInfoEnabled()) {
                        LOGGER.info("{} ({}) completed. Took {}.", work, Thread.currentThread(), TimeFormatting.formatDurationVerbose(duration));
                    }
                });
                if (!nodesRemaining) {
                    break;
                }
            }

            coordinationService.withStateLock(resourceLockState -> {
                if (releaseLeaseOnCompletion && workerLease.isLockedByCurrentThread()) {
                    workerLease.unlock();
                    return FINISHED;
                } else if (!releaseLeaseOnCompletion && !workerLease.isLockedByCurrentThread()) {
                    if (workerLease.tryLock()) {
                        return FINISHED;
                    } else {
                        return RETRY;
                    }
                } else {
                    return FINISHED;
                }
            });

            long total = totalTimer.getElapsedMillis();

            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("Execution worker [{}] finished, busy: {}, idle: {}", Thread.currentThread(), TimeFormatting.formatDurationVerbose(busy.get()), TimeFormatting.formatDurationVerbose(total - busy.get()));
            }
        }

        /**
         * Selects a node that's ready to execute and executes the provided action against it. If no node is ready, blocks until some
         * can be executed.
         *
         * @return {@code true} if there are more nodes waiting to execute, {@code false} if all nodes have been executed.
         */
        private boolean executeNextNode(final WorkerLease workerLease, final Action<Node> nodeExecutor) {
            final MutableReference<Node> selected = MutableReference.empty();
            final MutableBoolean nodesRemaining = new MutableBoolean();
            coordinationService.withStateLock(resourceLockState -> {
                if (cancellationToken.isCancellationRequested()) {
                    executionPlan.cancelExecution();
                }

                nodesRemaining.set(executionPlan.hasNodesRemaining());
                if (!nodesRemaining.get()) {
                    return FINISHED;
                }

                try {
                    selected.set(executionPlan.selectNext(workerLease, resourceLockState));
                } catch (Throwable t) {
                    resourceLockState.releaseLocks();
                    executionPlan.abortAllAndFail(t);
                    nodesRemaining.set(false);
                }

                if (selected.get() == null && nodesRemaining.get()) {
                    // Release worker lease while waiting
                    if (workerLease.isLockedByCurrentThread()) {
                        workerLease.unlock();
                        coordinationService.notifyStateChange();
                    }
                    return RETRY;
                } else {
                    return FINISHED;
                }
            });

            Node selectedNode = selected.get();
            if (selectedNode != null) {
                execute(selectedNode, nodeExecutor);
            }
            return nodesRemaining.get();
        }

        private void execute(final Node selected, Action<Node> nodeExecutor) {
            try {
                if (!selected.isComplete()) {
                    try {
                        nodeExecutor.execute(selected);
                    } catch (Throwable e) {
                        selected.setExecutionFailure(e);
                    }
                }
            } finally {
                coordinationService.withStateLock(state -> {
                    executionPlan.finishedExecuting(selected);
                    // Notify other threads that the node is finished as this may unblock further work
                    // or this might be the last node in the graph
                    coordinationService.notifyStateChange();
                    return FINISHED;
                });
            }
        }
    }
}
