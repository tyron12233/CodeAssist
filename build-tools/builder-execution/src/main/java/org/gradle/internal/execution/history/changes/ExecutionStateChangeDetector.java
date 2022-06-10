package org.gradle.internal.execution.history.changes;

import org.gradle.api.Describable;
import org.gradle.internal.execution.history.BeforeExecutionState;
import org.gradle.internal.execution.history.PreviousExecutionState;

public interface ExecutionStateChangeDetector {
    int MAX_OUT_OF_DATE_MESSAGES = 3;

    ExecutionStateChanges detectChanges(
            Describable executable,
            PreviousExecutionState lastExecution,
            BeforeExecutionState thisExecution,
            IncrementalInputProperties incrementalInputProperties
    );
}