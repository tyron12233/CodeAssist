package com.tyron.builder.execution.plan;

import com.tyron.builder.api.Action;

import java.util.Collection;

import java.util.Collection;

/**
 * Will be merged with {@link com.tyron.builder.internal.operations.BuildOperationExecutor}
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
