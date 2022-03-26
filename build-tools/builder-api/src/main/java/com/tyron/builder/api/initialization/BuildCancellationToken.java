package com.tyron.builder.api.initialization;

/*
 * Propagates notification that the build should be cancelled.
 */
public interface BuildCancellationToken {

    boolean isCancellationRequested();

    void cancel();

    /**
     * @return current state of cancellation request before callback was added.
     */
    boolean addCallback(Runnable cancellationHandler);

    /**
     * Removes a callback called when cancellation request happens.
     *
     * @param cancellationHandler removed callback.
     */
    void removeCallback(Runnable cancellationHandler);

}
