package org.gradle.internal.execution.steps;

import org.gradle.internal.execution.history.PreviousExecutionState;

import java.util.Optional;

public interface PreviousExecutionContext extends WorkspaceContext {
    /**
     * Returns the execution state after the previous execution if available.
     * Empty when execution history is not available.
     */
    Optional<PreviousExecutionState> getPreviousExecutionState();
}
