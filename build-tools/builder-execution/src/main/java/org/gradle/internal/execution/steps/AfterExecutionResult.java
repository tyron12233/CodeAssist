package org.gradle.internal.execution.steps;

import org.gradle.internal.execution.history.AfterExecutionState;

import java.util.Optional;

public interface AfterExecutionResult extends Result {
    /**
     * State after execution, or {@link Optional#empty()} if work is untracked.
     */
    Optional<AfterExecutionState> getAfterExecutionState();
}