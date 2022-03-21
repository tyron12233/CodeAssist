package com.tyron.builder.api.internal.execution;

import com.tyron.builder.api.Task;
import com.tyron.builder.api.execution.TaskExecutionGraph;
import com.tyron.builder.api.execution.plan.ExecutionPlan;
import com.tyron.builder.api.execution.plan.Node;

import java.util.Collection;
import java.util.List;
import java.util.Set;

public interface TaskExecutionGraphInternal extends TaskExecutionGraph {
    /**
     * Attaches the work that this graph will run. Fires events and no further tasks should be added.
     */
    void populate(ExecutionPlan plan);

    /**
     * Executes the given work. Discards the contents of this graph when completed. Should call {@link #populate(ExecutionPlan)} prior to
     * calling this method.
     *
     * @param taskFailures collection to collect task execution failures into. Does not need to be thread-safe
     */
    void execute(ExecutionPlan plan, Collection<? super Throwable> taskFailures);

    /**
     * Sets whether execution should continue if a task fails.
     */
    void setContinueOnFailure(boolean continueOnFailure);

    /**
     * Set of requested tasks.
     */
    Set<Task> getFilteredTasks();

    /**
     * Returns the number of work items in this graph.
     */
    int size();

    /**
     * Returns all of the work items in this graph scheduled for execution plus all
     * dependencies from other builds.
     */
    List<Node> getScheduledWorkPlusDependencies();
}