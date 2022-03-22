package com.tyron.builder.api.execution.plan;

import static com.tyron.builder.api.internal.resources.ResourceLockState.Disposition.FINISHED;
import static com.tyron.builder.api.internal.resources.ResourceLockState.Disposition.RETRY;

import com.tyron.builder.api.Action;
import com.tyron.builder.api.initialization.BuildCancellationToken;
import com.tyron.builder.api.internal.MutableReference;
import com.tyron.builder.api.internal.concurrent.CompositeStoppable;
import com.tyron.builder.api.internal.concurrent.ExecutorFactory;
import com.tyron.builder.api.internal.concurrent.ManagedExecutor;
import com.tyron.builder.api.internal.concurrent.Stoppable;
import com.tyron.builder.api.internal.logging.TreeFormatter;
import com.tyron.builder.api.internal.resources.ResourceLockCoordinationService;
import com.tyron.builder.api.internal.time.Time;
import com.tyron.builder.api.internal.time.TimeFormatting;
import com.tyron.builder.api.internal.time.Timer;
import com.tyron.builder.api.internal.work.WorkerLeaseRegistry;
import com.tyron.builder.api.internal.work.WorkerLeaseRegistry.WorkerLease;
import com.tyron.builder.api.internal.work.WorkerLeaseService;

import org.jetbrains.annotations.Nullable;

import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

public class DefaultPlanExecutor implements PlanExecutor, Stoppable {

    private static final Logger LOGGER = Logger.getLogger("DefaultPlanExecutor");

    private final int executorCount;
    private final WorkerLeaseService workerLeaseService;
    private final BuildCancellationToken cancellationToken;
    private final ResourceLockCoordinationService coordinationService;
    private final ManagedExecutor executor;
    private final Queue queue;
    private final AtomicBoolean workersStarted = new AtomicBoolean();

    public DefaultPlanExecutor(ExecutorFactory executorFactory,
                               WorkerLeaseService workerLeaseService,
                               BuildCancellationToken cancellationToken,
                               ResourceLockCoordinationService coordinationService) {
        this.executorCount = 1;
        this.workerLeaseService = workerLeaseService;
        this.cancellationToken = cancellationToken;
        this.coordinationService = coordinationService;
        this.queue = new Queue(coordinationService, false);
        this.executor = executorFactory.create("Execution worker");
    }

    @Override
    public void stop() {
        CompositeStoppable.stoppable(queue, executor).stop();
    }

    @Override
    public void process(ExecutionPlan executionPlan,
                        Collection<? super Throwable> failures,
                        Action<Node> nodeExecutor) {
        PlanDetails planDetails = new PlanDetails(executionPlan, nodeExecutor);
        queue.add(planDetails);

        maybeStartWorkers(queue, executor);

        WorkerLease currentWorkerLease = workerLeaseService.getCurrentWorkerLease();
        Queue thisPlanOnly = new Queue(coordinationService, true);
        thisPlanOnly.add(planDetails);

        new ExecutorWorker(thisPlanOnly, currentWorkerLease, cancellationToken, coordinationService, workerLeaseService).run();

        awaitCompletion(executionPlan, currentWorkerLease, failures);
    }


    @Override
    public void assertHealthy() {
        coordinationService.withStateLock(queue::assertHealthy);
    }


    /**
     * Blocks until all nodes in the plan have been processed. This method will only return when every node in the plan has either completed, failed or been skipped.
     */
    private void awaitCompletion(ExecutionPlan executionPlan, WorkerLease workerLease, Collection<? super Throwable> failures) {
        coordinationService.withStateLock(resourceLockState -> {
            if (executionPlan.allExecutionComplete()) {
                // Need to hold a worker lease in order to finish up
                if (!workerLease.isLockedByCurrentThread()) {
                    if (!workerLease.tryLock()) {
                        return RETRY;
                    }
                }
                executionPlan.collectFailures(failures);
                return FINISHED;
            } else {
                // Release worker lease (if held) while waiting for work to complete
                workerLease.unlock();
                return RETRY;
            }
        });
    }

    private void maybeStartWorkers(Queue queue, Executor executor) {
        if (workersStarted.compareAndSet(false, true)) {
            LOGGER.info("Using " + executorCount + " parallel executor threads");
            for (int i = 1; i < executorCount; i++) {
//                executor.execute(new ExecutorWorker(queue, null, cancellationToken, coordinationService, workerLeaseService));
            }
        }
    }

    private static class PlanDetails {
        final ExecutionPlan plan;
        final Action<Node> nodeExecutor;

        public PlanDetails(ExecutionPlan plan, Action<Node> nodeExecutor) {
            this.plan = plan;
            this.nodeExecutor = nodeExecutor;
        }
    }

    private static class WorkItem {
        final ExecutionPlan.NodeSelection selection;
        final ExecutionPlan plan;
        final Action<Node> executor;

        public WorkItem(ExecutionPlan.NodeSelection selection, ExecutionPlan plan, Action<Node> executor) {
            this.selection = selection;
            this.plan = plan;
            this.executor = executor;
        }
    }

    private static class Queue implements Closeable {
        private static final WorkItem NO_MORE_NODES = new WorkItem(ExecutionPlan.NO_MORE_NODES_TO_START, null, null);
        private static final WorkItem NO_NODES_READY = new WorkItem(ExecutionPlan.NO_NODES_READY_TO_START, null, null);

        private final ResourceLockCoordinationService coordinationService;
        private final boolean autoFinish;
        private boolean finished;
        private final LinkedList<PlanDetails> queues = new LinkedList<>();

        public Queue(ResourceLockCoordinationService coordinationService, boolean autoFinish) {
            this.coordinationService = coordinationService;
            this.autoFinish = autoFinish;
        }

        public ExecutionPlan.State executionState() {
            coordinationService.assertHasStateLock();
            Iterator<PlanDetails> iterator = queues.iterator();
            while (iterator.hasNext()) {
                PlanDetails details = iterator.next();
                ExecutionPlan.State state = details.plan.executionState();
                if (state == ExecutionPlan.State.NoMoreNodesToStart) {
                    iterator.remove();
                } else if (state == ExecutionPlan.State.MaybeNodesReadyToStart) {
                    return ExecutionPlan.State.MaybeNodesReadyToStart;
                }
            }
            if (nothingMoreToStart()) {
                return ExecutionPlan.State.NoMoreNodesToStart;
            } else {
                return ExecutionPlan.State.NoNodesReadyToStart;
            }
        }

        public WorkItem selectNext() {
            coordinationService.assertHasStateLock();
            Iterator<PlanDetails> iterator = queues.iterator();
            while (iterator.hasNext()) {
                PlanDetails details = iterator.next();
                ExecutionPlan.NodeSelection selection = details.plan.selectNext();
                if (selection == ExecutionPlan.NO_MORE_NODES_TO_START) {
                    iterator.remove();
                } else if (selection != ExecutionPlan.NO_NODES_READY_TO_START) {
                    return new WorkItem(selection, details.plan, details.nodeExecutor);
                }
            }
            if (nothingMoreToStart()) {
                return NO_MORE_NODES;
            } else {
                return NO_NODES_READY;
            }
        }

        private boolean nothingMoreToStart() {
            return finished || (autoFinish && queues.isEmpty());
        }

        public void add(PlanDetails planDetails) {
            coordinationService.withStateLock(() -> {
                if (finished) {
                    throw new IllegalStateException("This queue has been closed.");
                }
                // Assume that the plan is required by those plans already running and add to the head of the queue
                queues.addFirst(planDetails);
                // Signal to the worker threads that work may be available
                coordinationService.notifyStateChange();
            });
        }

        @Override
        public void close() throws IOException {
            coordinationService.withStateLock(() -> {
                finished = true;
                if (!queues.isEmpty()) {
                    for (PlanDetails queue : queues) {
                        if (queue.plan.executionState() != ExecutionPlan.State.NoMoreNodesToStart) {
                            throw new IllegalStateException("Not all work has completed.");
                        }
                    }
                }
                // Signal to the worker threads that no more work is available
                coordinationService.notifyStateChange();
            });
        }

        public void cancelExecution() {
            coordinationService.assertHasStateLock();
            for (PlanDetails details : queues) {
                details.plan.cancelExecution();
            }
        }

        public void abortAllAndFail(Throwable t) {
            coordinationService.assertHasStateLock();
            for (PlanDetails details : queues) {
                details.plan.abortAllAndFail(t);
            }
        }

        public void assertHealthy() {
            coordinationService.assertHasStateLock();
            if (queues.isEmpty()) {
                return;
            }
            List<ExecutionPlan.Diagnostics> allDiagnostics = new ArrayList<>(queues.size());
            for (PlanDetails details : queues) {
                ExecutionPlan.Diagnostics diagnostics = details.plan.healthDiagnostics();
                if (diagnostics.canMakeProgress()) {
                    return;
                }
                allDiagnostics.add(diagnostics);
            }

            // Log some diagnostic information to the console, in addition to aborting execution with an exception which will also be logged
            // Given that the execution infrastructure is in an unhealthy state, it may not shut down cleanly and report the execution.
            // So, log some details here just in case
            TreeFormatter formatter = new TreeFormatter();
            formatter.node("Unable to make progress running work. The following items are queued for execution but none of them can be started:");
            formatter.startChildren();
            for (ExecutionPlan.Diagnostics diagnostics : allDiagnostics) {
                for (String node : diagnostics.getQueuedNodes()) {
                    formatter.node(node);
                }
            }
            formatter.endChildren();
            System.out.println(formatter);

            IllegalStateException failure = new IllegalStateException("Unable to make progress running work. There are items queued for execution but none of them can be started");
            for (PlanDetails details : queues) {
                details.plan.abortAllAndFail(failure);
            }
        }
    }

    private static class ExecutorWorker implements Runnable {
        private final Queue queue;
        private WorkerLease workerLease;
        private final BuildCancellationToken cancellationToken;
        private final ResourceLockCoordinationService coordinationService;
        private final WorkerLeaseService workerLeaseService;

        private ExecutorWorker(
                Queue queue,
                @Nullable WorkerLease workerLease,
                BuildCancellationToken cancellationToken,
                ResourceLockCoordinationService coordinationService,
                WorkerLeaseService workerLeaseService
        ) {
            this.queue = queue;
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
                WorkItem workItem = getNextItem(workerLease);
                if (workItem == null) {
                    break;
                }
                Node node = workItem.selection.getNode();
                LOGGER.info(node + " (" + Thread.currentThread() +") started.");
                executionTimer.reset();
                execute(node, workItem.plan, workItem.executor);
                long duration = executionTimer.getElapsedMillis();
                busy.addAndGet(duration);
                if (LOGGER.isLoggable(Level.INFO)) {
                    LOGGER.info(node + " (" +  Thread.currentThread() + ") completed. Took " + TimeFormatting
                            .formatDurationVerbose(duration));
                }
            }

            if (releaseLeaseOnCompletion) {
                coordinationService.withStateLock(() -> workerLease.unlock());
            }

            long total = totalTimer.getElapsedMillis();

            if (LOGGER.isLoggable(Level.INFO)) {
                LOGGER.info("Execution worker [" + Thread.currentThread() + "] finished, busy: " + TimeFormatting.formatDurationVerbose(busy.get()) + ", idle: " + TimeFormatting.formatDurationVerbose(total - busy.get()));
            }
        }

        /**
         * Selects a node that's ready to execute and executes the provided action against it. If no node is ready, blocks until some
         * can be executed.
         *
         * @return The next node to execute or {@code null} when there are no nodes remaining
         */
        @Nullable
        private WorkItem getNextItem(final WorkerLease workerLease) {
            final MutableReference<WorkItem> selected = MutableReference.empty();
            coordinationService.withStateLock(resourceLockState -> {
                if (cancellationToken.isCancellationRequested()) {
                    queue.cancelExecution();
                }

                ExecutionPlan.State state = queue.executionState();
                if (state == ExecutionPlan.State.NoMoreNodesToStart) {
                    return FINISHED;
                } else if (state == ExecutionPlan.State.NoNodesReadyToStart) {
                    // Release worker lease while waiting
                    if (workerLease.isLockedByCurrentThread()) {
                        workerLease.unlock();
                    }
                    return RETRY;
                }
                // Else there may be nodes ready, acquire a worker lease

                boolean hasWorkerLease = workerLease.isLockedByCurrentThread();
                if (!hasWorkerLease && !workerLease.tryLock()) {
                    // Cannot get a lease to run work
                    return RETRY;
                }

                WorkItem workItem;
                try {
                    workItem = queue.selectNext();
                } catch (Throwable t) {
                    resourceLockState.releaseLocks();
                    queue.abortAllAndFail(t);
                    return FINISHED;
                }
                if (workItem.selection == ExecutionPlan.NO_MORE_NODES_TO_START) {
                    return FINISHED;
                } else if (workItem.selection == ExecutionPlan.NO_NODES_READY_TO_START) {
                    // Release worker lease while waiting
                    workerLease.unlock();
                    return RETRY;
                }

                selected.set(workItem);
                return FINISHED;
            });

            return selected.get();
        }

        private void execute(final Node selected, ExecutionPlan executionPlan, Action<Node> nodeExecutor) {
            try {
                try {
                    nodeExecutor.execute(selected);
                } catch (Throwable e) {
                    selected.setExecutionFailure(e);
                }
            } finally {
                coordinationService.withStateLock(() -> {
                    try {
                        executionPlan.finishedExecuting(selected);
                    } catch (Throwable t) {
                        queue.abortAllAndFail(t);
                    }
                    // Notify other threads that the node is finished as this may unblock further work
                    // or this might be the last node in the graph
                    coordinationService.notifyStateChange();
                });
            }
        }
    }
}
