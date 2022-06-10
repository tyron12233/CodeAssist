package org.gradle.internal.execution.steps;


import org.gradle.internal.execution.history.changes.InputChangesInternal;

import java.util.Optional;

public interface InputChangesContext extends ValidationFinishedContext {
    Optional<InputChangesInternal> getInputChanges();
    boolean isIncrementalExecution();
}

