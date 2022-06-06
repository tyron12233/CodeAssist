package com.tyron.builder.internal.execution.history.changes;

import com.google.common.collect.ImmutableList;
import com.tyron.builder.internal.execution.history.BeforeExecutionState;

/**
 * Represents the complete changes in execution state
 */
public interface ExecutionStateChanges {

    /**
     * Returns all change messages for inputs and outputs.
     */
    ImmutableList<String> getChangeDescriptions();

    InputChangesInternal createInputChanges();

    BeforeExecutionState getBeforeExecutionState();

    static ExecutionStateChanges incremental(
            ImmutableList<String> changeDescriptions,
            BeforeExecutionState beforeExecutionState,
            InputFileChanges inputFileChanges,
            IncrementalInputProperties incrementalInputProperties
    ) {
        return new ExecutionStateChanges() {
            @Override
            public ImmutableList<String> getChangeDescriptions() {
                return changeDescriptions;
            }

            @Override
            public InputChangesInternal createInputChanges() {
                return new IncrementalInputChanges(inputFileChanges, incrementalInputProperties);
            }

            @Override
            public BeforeExecutionState getBeforeExecutionState() {
                return beforeExecutionState;
            }
        };
    }

    static ExecutionStateChanges nonIncremental(
            ImmutableList<String> changeDescriptions,
            BeforeExecutionState beforeExecutionState,
            IncrementalInputProperties incrementalInputProperties
    ) {
        return new ExecutionStateChanges() {
            @Override
            public ImmutableList<String> getChangeDescriptions() {
                return changeDescriptions;
            }

            @Override
            public InputChangesInternal createInputChanges() {
                return new NonIncrementalInputChanges(beforeExecutionState.getInputFileProperties(), incrementalInputProperties);
            }

            @Override
            public BeforeExecutionState getBeforeExecutionState() {
                return beforeExecutionState;
            }
        };
    }
}