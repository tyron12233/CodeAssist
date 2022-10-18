package org.gradle.internal.operations;


import org.gradle.internal.concurrent.ManagedExecutor;

public interface BuildOperationQueueFactory {
    <T extends BuildOperation> BuildOperationQueue<T> create(ManagedExecutor executor, boolean allowAccessToProjectState, BuildOperationQueue.QueueWorker<T> worker);
}