package com.tyron.builder.internal.operations;

/**
 * A worker that can run build operations.
 *
 * Implementations must be thread-safe.
 */
public interface BuildOperationWorker<O extends BuildOperation> {
    void execute(O buildOperation, BuildOperationContext context) throws Exception;
}