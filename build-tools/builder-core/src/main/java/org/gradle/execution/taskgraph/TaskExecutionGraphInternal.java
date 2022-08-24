package org.gradle.execution.taskgraph;

import org.gradle.api.Task;
import org.gradle.api.execution.TaskExecutionGraph;
import org.gradle.execution.plan.ExecutionPlan;
import org.gradle.execution.plan.Node;
import org.gradle.internal.build.ExecutionResult;

import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

public interface TaskExecutionGraphInternal extends TaskExecutionGraph {
    /**
     * Attaches the work that this graph will run. Fires events and no further tasks should be added.
     */
    void populate(ExecutionPlan plan);

    /**
     * Executes the given work. Discards the contents of this graph when completed. Should call {@link #populate(ExecutionPlan)} prior to
     * calling this method.
     */
    ExecutionResult<Void> execute(ExecutionPlan plan);

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
    void visitScheduledNodes(Consumer<List<Node>> visitor);
}
