package com.tyron.builder.internal.execution.steps;

import com.google.common.collect.ImmutableList;
import com.tyron.builder.internal.execution.history.changes.ExecutionStateChanges;

import java.util.Optional;

public interface IncrementalChangesContext extends CachingContext {

    /**
     * Returns the reasons to re-execute the work, empty if there's no reason to re-execute.
     */
    ImmutableList<String> getRebuildReasons();

    /**
     * Returns changes detected between the execution state after the last execution and before the current execution.
     * Empty if changes couldn't be detected (e.g. because history was unavailable).
     */
    Optional<ExecutionStateChanges> getChanges();
}