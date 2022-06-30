package com.tyron.builder.internal.work;

import com.tyron.builder.internal.concurrent.Stoppable;

/**
 * Represents a queue of executions that can run when a provided resource lock can be acquired.  The typical use case would
 * be that a worker lease must be acquired before execution.
 */
public interface ConditionalExecutionQueue<T> extends Stoppable {
    /**
     * Submit a new conditional execution to the queue.  The execution will occur asynchronously when the provided
     * resource lock (see {@link ConditionalExecution#getResourceLock()}) can be acquired.  On completion,
     * {@link ConditionalExecution#complete()} will be called.
     */
    void submit(ConditionalExecution<T> execution);

    /**
     * Expand the execution queue worker pool.  This should be called before an execution in the queue is blocked waiting
     * on another execution (e.g. work that submits and waits on other work).
     */
    void expand();
}
