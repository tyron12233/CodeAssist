package com.tyron.builder.api.tasks;

/**
 * <p>A <code>StopExecutionException</code> is thrown by a {@link com.tyron.builder.api.Action} or task action closure to
 * stop execution of the current task and start execution of the next task. This allows, for example, precondition
 * actions to be added to a task which abort execution of the task if the preconditions are not met.</p>
 *
 * <p>Note that throwing this exception does not fail the execution of the task or the build.</p>
 */
public class StopExecutionException extends RuntimeException {

    public StopExecutionException() {
        super();
    }

    public StopExecutionException(String message) {
        super(message);
    }

}
