package org.gradle.execution.plan;

import org.gradle.api.Action;

import java.util.Collection;

import java.util.Collection;

/**
 * Will be merged with {@link org.gradle.internal.operations.BuildOperationExecutor}
 */
public interface PlanExecutor {

    /**
     * Executes an {@link ExecutionPlan}.
     *
     * @param executionPlan the plan to execute.
     * @param failures collection to collect failures happening during execution into. Does not need to be thread-safe.
     * @param nodeExecutor the actual executor responsible to execute the nodes. Must be thread-safe.
     */
    void process(ExecutionPlan executionPlan, Collection<? super Throwable> failures, Action<Node> nodeExecutor);
}
