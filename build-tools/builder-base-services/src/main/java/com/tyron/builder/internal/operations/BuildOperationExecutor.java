package com.tyron.builder.internal.operations;

import com.tyron.builder.api.Action;

public interface BuildOperationExecutor extends BuildOperationRunner {
    /**
     * Runs the given build operation synchronously. Invokes the given operation from the current thread.
     *
     * <p>Rethrows any exception thrown by the action.
     * Runtime exceptions are rethrown as is.
     * Checked exceptions are wrapped in {@link BuildOperationInvocationException}.</p>
     */
    void run(RunnableBuildOperation buildOperation);

    /**
     * Calls the given build operation synchronously. Invokes the given operation from the current thread.
     * Returns the result.
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
     * Returns the state of the build operation currently running on this thread. Can be used as parent of a new build operation
     * started in a different thread (or process). See {@link BuildOperationDescriptor.Builder#parent(BuildOperationRef)}
     *
     * @throws IllegalStateException When the current thread is not executing an operation.
     */
    BuildOperationRef getCurrentOperation();

    /**
     * Submits an arbitrary number of runnable operations, created synchronously by the scheduling action, to be executed in the global
     * build operation thread pool. Operations may execute concurrently. Blocks until all operations are complete.
     *
     * <p>Actions are not permitted to access any mutable project state. Generally, this is preferred.</p>
     */
    <O extends RunnableBuildOperation> void runAll(Action<BuildOperationQueue<O>> schedulingAction);

    /**
     * Overload allowing {@link BuildOperationConstraint} to be specified.
     *
     * @see BuildOperationExecutor#runAllWithAccessToProjectState(Action)
     */
    <O extends RunnableBuildOperation> void runAll(Action<BuildOperationQueue<O>> schedulingAction, BuildOperationConstraint buildOperationConstraint);

    /**
     * Same as {@link #runAll(Action)}. However, the actions are allowed to access mutable project state. In general, this is more likely to
     * result in deadlocks and other flaky behaviours.
     *
     * <p>See {@link org.gradle.internal.resources.ProjectLeaseRegistry#whileDisallowingProjectLockChanges(Factory)} for more details.
     */
    <O extends RunnableBuildOperation> void runAllWithAccessToProjectState(Action<BuildOperationQueue<O>> schedulingAction);

    /**
     * Overload allowing {@link BuildOperationConstraint} to be specified.
     *
     * @see BuildOperationExecutor#runAllWithAccessToProjectState(Action)
     */
    <O extends RunnableBuildOperation> void runAllWithAccessToProjectState(Action<BuildOperationQueue<O>> schedulingAction, BuildOperationConstraint buildOperationConstraint);

    /**
     * Submits an arbitrary number of operations, created synchronously by the scheduling action, to be executed by the supplied
     * worker in the global build operation thread pool. Operations may execute concurrently, so the worker should be thread-safe.
     * Blocks until all operations are complete.
     *
     * <p>Actions are not permitted to access any mutable project state. Generally, this is preferred.</p>
     */
    <O extends BuildOperation> void runAll(BuildOperationWorker<O> worker, Action<BuildOperationQueue<O>> schedulingAction);

    /**
     * Overload allowing {@link BuildOperationConstraint} to be specified.
     *
     * @see BuildOperationExecutor#runAll(BuildOperationWorker, Action)
     */
    <O extends BuildOperation> void runAll(BuildOperationWorker<O> worker, Action<BuildOperationQueue<O>> schedulingAction, BuildOperationConstraint buildOperationConstraint);
}