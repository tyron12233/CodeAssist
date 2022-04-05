package com.tyron.builder.api.internal.execution.steps;

import com.tyron.builder.api.internal.execution.history.AfterExecutionState;

import java.util.Optional;

public interface AfterExecutionResult extends Result {
    /**
     * State after execution, or {@link Optional#empty()} if work is untracked.
     */
    Optional<AfterExecutionState> getAfterExecutionState();
}
