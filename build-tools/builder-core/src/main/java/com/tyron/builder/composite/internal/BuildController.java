package com.tyron.builder.composite.internal;

import com.tyron.builder.internal.build.BuildLifecycleController;
import com.tyron.builder.internal.build.ExecutionResult;
import com.tyron.builder.internal.build.ExportedTaskNode;

import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;

public interface BuildController {
    /**
     * Adds tasks and nodes to the work graph of this build.
     */
    void populateWorkGraph(Consumer<? super BuildLifecycleController.WorkGraphBuilder> action);

    /**
     * Queues the given task for execution. Does not schedule the task, use {@link #scheduleQueuedTasks()} for this.
     */
    void queueForExecution(ExportedTaskNode taskNode);

    /**
     * Schedules any queued tasks. When this method returns true, then some tasks where scheduled for this build and
     * this method should be called for all other builds in the tree as they may now have queued tasks.
     *
     * @return true if any tasks were scheduled, false if not.
     */
    boolean scheduleQueuedTasks();

    /**
     * Prepares the work graph, once all tasks have been scheduled.
     */
    void finalizeWorkGraph();

    /**
     * Must call {@link #scheduleQueuedTasks()} prior to calling this method.
     */
    void startExecution(ExecutorService executorService);

    /**
     * Awaits completion of work execution, returning any failures in the result.
     */
    ExecutionResult<Void> awaitCompletion();
}
