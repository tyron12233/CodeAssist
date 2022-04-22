package com.tyron.builder.internal.operations;

/**
 * An individual active, single use, queue of build operations.
 * <p>
 * The queue is active in that operations are potentially executed as soon as they are added.
 * The queue is single use in that no further work can be added once {@link #waitForCompletion()} has completed.
 * <p>
 * A queue instance is threadsafe. Build operations can submit further operations to the queue but must not block waiting for them to complete.
 *
 * @param <T> type of build operations to hold
 */
public interface BuildOperationQueue<T extends BuildOperation> {

    /**
     * Adds an operation to be executed, potentially executing it instantly.
     *
     * @param operation operation to execute
     */
    void add(T operation);

    /**
     * Cancels all queued operations in this queue.  Any operations that have started will be allowed to complete.
     */
    void cancel();

    /**
     * Waits for all previously added operations to complete.
     * <p>
     * On failure, some effort is made to cancel any operations that have not started.
     *
     * @throws MultipleBuildOperationFailures if <em>any</em> operation failed
     */
    void waitForCompletion() throws MultipleBuildOperationFailures;

    /**
     * Sets the location of a log file where build operation output can be found.  For use in exceptions.
     */
    void setLogLocation(String logLocation);

    interface QueueWorker<O extends BuildOperation> {
        void execute(O buildOperation);
        String getDisplayName();
    }
}
