package com.tyron.builder.internal.work;

import com.tyron.builder.internal.exceptions.MultiCauseException;
import com.tyron.builder.internal.operations.BuildOperationRef;

import java.util.List;

/**
 * Allows asynchronous work to be tracked based on the build operation it is associated with.
 */
public interface AsyncWorkTracker {
    enum ProjectLockRetention {
        RETAIN_PROJECT_LOCKS, RELEASE_PROJECT_LOCKS, RELEASE_AND_REACQUIRE_PROJECT_LOCKS
    }
    /**
     * Register a new item of asynchronous work with the provided build operation.
     *
     * @param operation - The build operation to associate the asynchronous work with
     * @param completion - The completion of the asynchronous work
     * @throws IllegalStateException when new work is submitted for an operation while another thread is waiting in {@link #waitForCompletion(BuildOperationRef, boolean)} for the same operation.
     */
    void registerWork(BuildOperationRef operation, AsyncWorkCompletion completion);

    /**
     * Blocks waiting for the completion of all items of asynchronous work associated with the provided build operation.
     * Only waits for work that has been registered at the moment the method is called.  In the event that there are failures in
     * the asynchronous work, a {@link MultiCauseException} will be thrown with any exceptions
     * thrown.
     *
     * @param operation - The build operation whose asynchronous work should be completed
     * @param lockRetention - How project locks should be treated while waiting on work
     */
    void waitForCompletion(BuildOperationRef operation, ProjectLockRetention lockRetention);

    /**
     * Blocks waiting for the completion of the specified items of asynchronous work.
     * Only waits for work in the list at the moment the method is called.  In the event that there are failures in
     * the asynchronous work, a {@link MultiCauseException} will be thrown with any exceptions
     * thrown.
     *
     * @param workCompletions - The items of work that should be waited on
     * @param lockRetention - How project locks should be treated while waiting on work
     */
    void waitForCompletion(BuildOperationRef operation, List<AsyncWorkCompletion> workCompletions, ProjectLockRetention lockRetention);

    /**
     * Returns true if the given operation has work registered that has not completed.
     */
    boolean hasUncompletedWork(BuildOperationRef operation);
}