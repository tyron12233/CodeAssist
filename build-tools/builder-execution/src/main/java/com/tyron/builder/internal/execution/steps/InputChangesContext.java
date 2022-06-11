package com.tyron.builder.internal.execution.steps;


import com.tyron.builder.internal.execution.history.changes.InputChangesInternal;

import java.util.Optional;

public interface InputChangesContext extends ValidationFinishedContext {
    Optional<InputChangesInternal> getInputChanges();
    boolean isIncrementalExecution();
}

