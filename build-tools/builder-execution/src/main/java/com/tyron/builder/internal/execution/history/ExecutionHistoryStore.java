package com.tyron.builder.internal.execution.history;

import java.util.Optional;

public interface ExecutionHistoryStore {
    Optional<PreviousExecutionState> load(String key);

    void store(
            String key,
            boolean successful,
            AfterExecutionState executionState
    );

    void remove(String key);
}

