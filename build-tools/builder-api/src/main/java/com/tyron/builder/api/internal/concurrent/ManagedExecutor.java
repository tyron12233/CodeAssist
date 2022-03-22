package com.tyron.builder.api.internal.concurrent;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

public interface ManagedExecutor extends AsyncStoppable, ExecutorService {
    /**
     * Stops accepting new jobs and blocks until all currently executing jobs have been completed.
     */
    @Override
    void stop();

    /**
     * Stops accepting new jobs and blocks until all currently executing jobs have been completed. Once the given
     * timeout has been reached, forcefully stops remaining jobs and throws an exception.
     *
     * @throws IllegalStateException on timeout.
     */
    void stop(int timeoutValue, TimeUnit timeoutUnits) throws IllegalStateException;

    /**
     * Sets the keep alive time for the thread pool of the executor.
     */
    void setKeepAlive(int timeout, TimeUnit timeUnit);
}