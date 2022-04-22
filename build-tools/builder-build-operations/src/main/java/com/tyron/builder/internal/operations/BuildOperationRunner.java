package com.tyron.builder.internal.operations;

import org.jetbrains.annotations.Nullable;

/**
 * Runs build operations: the pieces of work that make up a build.
 * Build operations can be nested inside other build operations.
 */
public interface BuildOperationRunner {
    /**
     * Runs the given build operation.
     *
     * <p>Rethrows any exception thrown by the action.
     * Runtime exceptions are rethrown as is.
     * Checked exceptions are wrapped in {@link BuildOperationInvocationException}.</p>
     */
    void run(RunnableBuildOperation buildOperation);

    /**
     * Calls the given build operation, returns the result.
     *
     * <p>Rethrows any exception thrown by the action.
     * Runtime exceptions are rethrown as is.
     * Checked exceptions are wrapped in {@link BuildOperationInvocationException}.</p>
     */
    <T> T call(CallableBuildOperation<T> buildOperation);

    /**
     * Starts an operation that can be finished later.
     *
     * When a parent operation is finished any unfinished child operations will be failed.
     */
    BuildOperationContext start(BuildOperationDescriptor.Builder descriptor);

    /**
     * Executes the given build operation with the given worker, returns the result.
     */
    <O extends BuildOperation> void execute(O buildOperation, BuildOperationWorker<O> worker, @Nullable BuildOperationState defaultParent);
}