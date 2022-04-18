package com.tyron.builder.internal.operations;


import com.tyron.builder.api.internal.concurrent.ManagedExecutor;

public interface BuildOperationQueueFactory {
    <T extends BuildOperation> BuildOperationQueue<T> create(ManagedExecutor executor, boolean allowAccessToProjectState, BuildOperationQueue.QueueWorker<T> worker);
}