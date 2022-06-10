package org.gradle.internal.execution.timeout;

import org.gradle.api.Describable;
import org.gradle.internal.concurrent.Stoppable;
import org.gradle.internal.operations.BuildOperationRef;

import org.jetbrains.annotations.Nullable;

import java.time.Duration;

/**
 * Manages timeouts for threads, interrupting them if the timeout is exceeded.
 */
public interface TimeoutHandler extends Stoppable {
    /**
     * Starts a timeout for the given thread. The thread is interrupted if the given timeout is exceeded.
     * The returned {@link Timeout} object must be used to stop the timeout once the thread has completed
     * the work that this timeout was supposed to limit, otherwise it may be interrupted doing
     * some other work later.
     */
    Timeout start(Thread taskExecutionThread, Duration timeoutInMillis, Describable workUnitDescription, @Nullable BuildOperationRef buildOperationRef);

    /**
     * Stops all {@link Timeout}s created from this handler.
     */
    @Override
    void stop();
}