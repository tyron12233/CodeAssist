package com.tyron.builder.api;

import org.jetbrains.annotations.Nullable;

/**
 * {@code ProjectState} provides information about the execution state of a project.
 */
public interface ProjectState {
    /**
     * <p>Returns true if this project has been evaluated.</p>
     *
     * @return true if this project has been evaluated.
     */
    boolean getExecuted();

    /**
     * Returns the exception describing the project failure, if any.
     *
     * @return The exception, or null if project evaluation did not fail.
     */
    @Nullable
    Throwable getFailure();

    /**
     * Throws the project failure, if any. Does nothing if the project did not fail.
     */
    void rethrowFailure();
}