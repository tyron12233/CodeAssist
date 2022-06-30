package com.tyron.builder.internal.execution.history.changes;

import com.tyron.builder.api.Describable;
import com.tyron.builder.internal.execution.history.BeforeExecutionState;
import com.tyron.builder.internal.execution.history.PreviousExecutionState;

public interface ExecutionStateChangeDetector {
    int MAX_OUT_OF_DATE_MESSAGES = 3;

    ExecutionStateChanges detectChanges(
            Describable executable,
            PreviousExecutionState lastExecution,
            BeforeExecutionState thisExecution,
            IncrementalInputProperties incrementalInputProperties
    );
}