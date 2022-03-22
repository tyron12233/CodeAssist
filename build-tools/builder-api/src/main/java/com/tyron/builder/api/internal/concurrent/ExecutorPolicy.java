package com.tyron.builder.api.internal.concurrent;

import java.lang.reflect.UndeclaredThrowableException;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

/**
 * Controls the behavior of an executor when a task is executed and an executor is stopped.
 */
public interface ExecutorPolicy {
    /**
     * Special behavior when a task is executed.
     *
     * The Runnable's run() needs to be called from this method.
     */
    void onExecute(Runnable command);

    /**
     * Special behavior when a task is executed.
     *
     * The Callable's call() needs to be called from this method.
     */
    <T> T onExecute(Callable<T> command) throws Exception;

    /**
     * Special behavior after an executor is stopped.
     *
     * This is called after the underlying Executor has been stopped.
     */
    void onStop();

    /**
     * Runs the Runnable, catches all Throwables and logs them.
     *
     * The first exception caught during onExecute(), will be rethrown in onStop().
     */
    class CatchAndRecordFailures implements ExecutorPolicy {
        private static final Logger LOGGER = Logger.getLogger("CatchAndRecordFailures");
        private final AtomicReference<Throwable> failure = new AtomicReference<Throwable>();

        @Override
        public void onExecute(Runnable command) {
            try {
                command.run();
            } catch (Throwable throwable) {
                onFailure(String.format("Failed to execute %s.", command), throwable);
            }
        }

        @Override
        public <T> T onExecute(Callable<T> command) throws Exception {
            try {
                return command.call();
            } catch (Exception exception) {
                onFailure(String.format("Failed to execute %s.", command), exception);
                throw exception;
            } catch(Throwable throwable) {
                onFailure(String.format("Failed to execute %s.", command), throwable);
                throw new UndeclaredThrowableException(throwable);
            }
        }

        public void onFailure(String message, Throwable throwable) {
            // Capture or log all failures
            if (!failure.compareAndSet(null, throwable)) {
                LOGGER.severe(message + " " + throwable);
            }
        }

        @Override
        public void onStop() {
            // Rethrow the first failure
            Throwable failure = this.failure.getAndSet(null);
            if (failure != null) {
                throw new RuntimeException(failure);
            }
        }
    }
}