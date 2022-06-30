package com.tyron.builder.workers;

import com.tyron.builder.internal.exceptions.Contextual;
import com.tyron.builder.internal.exceptions.DefaultMultiCauseException;

/**
 * Indicates that a failure occurred during execution of work in a worker.
 *
 * @since 3.5
 */
@Contextual
public class WorkerExecutionException extends DefaultMultiCauseException {
    public WorkerExecutionException(String message) {
        super(message);
    }

    public WorkerExecutionException(String message, Throwable... causes) {
        super(message, causes);
    }

    public WorkerExecutionException(String message, Iterable<? extends Throwable> causes) {
        super(message, causes);
    }

}
