package com.tyron.builder.api.internal.execution.steps;


import com.tyron.builder.api.internal.execution.history.InputChangesInternal;

import java.util.Optional;

public interface InputChangesContext extends ValidationFinishedContext {
    Optional<InputChangesInternal> getInputChanges();
    boolean isIncrementalExecution();
}

