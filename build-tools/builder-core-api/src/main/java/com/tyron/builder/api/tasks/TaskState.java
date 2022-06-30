package com.tyron.builder.api.tasks;

import com.tyron.builder.api.Task;

/**
 * {@code TaskState} provides information about the execution state of a {@link Task}. You can obtain a
 * {@code TaskState} instance by calling {@link Task#getState()}.
 */
public interface TaskState {
    /**
     * <p>Returns true if this task has been executed.</p>
     *
     * @return true if this task has been executed.
     */
    boolean getExecuted();

    /**
     * Returns the exception describing the task failure, if any.
     *
     * @return The exception, or null if the task did not fail.
     */
    Throwable getFailure();

    /**
     * Throws the task failure, if any. Does nothing if the task did not fail.
     */
    void rethrowFailure();

    /**
     * <p>Checks if the task actually did any work.  Even if a task executes, it may determine that it has nothing to
     * do.  For example, a compilation task may determine that source files have not changed since the last time a the
     * task was run.</p>
     *
     * @return true if this task has been executed and did any work.
     */
    boolean getDidWork();

    /**
     * Returns true if the execution of this task was skipped for some reason.
     *
     * @return true if this task has been executed and skipped.
     */
    boolean getSkipped();

    /**
     * Returns a message describing why the task was skipped.
     *
     * @return the message. returns null if the task was not skipped.
     */
    String getSkipMessage();

    /**
     * Returns true if the execution of this task was skipped because the task was up-to-date.
     *
     * @return true if this task has been considered up-to-date
     * @since 2.5
     */
    boolean getUpToDate();

    /**
     * Returns true if the execution of this task was skipped due to task inputs are empty.
     *
     * @return true if this task has no input files assigned
     * @since 3.4
     */
    boolean getNoSource();
}